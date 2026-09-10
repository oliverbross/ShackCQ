// SPDX-License-Identifier: GPL-3.0-only
#include <QApplication>
#include <QCheckBox>
#include <QComboBox>
#include <QCoreApplication>
#include <QFont>
#include <QFormLayout>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QHostInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLabel>
#include <QLineEdit>
#include <QMainWindow>
#include <QMessageBox>
#include <QPlainTextEdit>
#include <QProcess>
#include <QPushButton>
#include <QTimer>
#include <QVBoxLayout>
#include <QWidget>

namespace {
struct CommandResult {
  int exitCode{-1};
  QByteArray output;
};

class AgentWindow final : public QMainWindow {
 public:
  explicit AgentWindow(bool smokeTest = false) {
    setWindowTitle(QStringLiteral("ShackCQ Agent"));
    resize(680, 650);

    auto *root = new QWidget(this);
    auto *layout = new QVBoxLayout(root);
    auto *heading = new QLabel(QStringLiteral("Connect this Mac to ShackCQ"), root);
    QFont headingFont = heading->font();
    headingFont.setPointSize(20);
    headingFont.setBold(true);
    heading->setFont(headingFont);
    layout->addWidget(heading);
    auto *intro = new QLabel(
        QStringLiteral("Pair the Agent with ShackCQ, then choose the radio connected to this Mac. "
                       "Setup never enables PTT, TUNE, or transmit."), root);
    intro->setWordWrap(true);
    layout->addWidget(intro);

    auto *cloudGroup = new QGroupBox(QStringLiteral("1. ShackCQ account"), root);
    auto *cloudForm = new QFormLayout(cloudGroup);
    m_origin = new QLineEdit(QStringLiteral("https://shackcq.com"), cloudGroup);
    m_agentName = new QLineEdit(QHostInfo::localHostName(), cloudGroup);
    m_pairingCode = new QLineEdit(cloudGroup);
    m_pairingCode->setPlaceholderText(QStringLiteral("One-time code shown in ShackCQ"));
    m_pairingCode->setClearButtonEnabled(true);
    cloudForm->addRow(QStringLiteral("ShackCQ address"), m_origin);
    cloudForm->addRow(QStringLiteral("Agent name"), m_agentName);
    cloudForm->addRow(QStringLiteral("Pairing code"), m_pairingCode);
    auto *cloudButtons = new QHBoxLayout;
    auto *pairButton = new QPushButton(QStringLiteral("Pair with ShackCQ"), cloudGroup);
    auto *unpairButton = new QPushButton(QStringLiteral("Unpair"), cloudGroup);
    cloudButtons->addWidget(pairButton);
    cloudButtons->addWidget(unpairButton);
    cloudForm->addRow(QString(), cloudButtons);
    layout->addWidget(cloudGroup);

    auto *radioGroup = new QGroupBox(QStringLiteral("2. Radio"), root);
    auto *radioForm = new QFormLayout(radioGroup);
    m_model = new QComboBox(radioGroup);
    m_model->setSizeAdjustPolicy(QComboBox::AdjustToMinimumContentsLengthWithIcon);
    m_model->setMinimumContentsLength(35);
    m_route = new QComboBox(radioGroup);
    m_route->setEditable(true);
    m_route->lineEdit()->setPlaceholderText(QStringLiteral("Serial port or supported network route"));
    m_baud = new QComboBox(radioGroup);
    for (const QString &baud : {QStringLiteral("4800"), QStringLiteral("9600"),
                                QStringLiteral("19200"), QStringLiteral("38400"),
                                QStringLiteral("57600"), QStringLiteral("115200")})
      m_baud->addItem(baud);
    m_baud->setCurrentText(QStringLiteral("38400"));
    m_autoConnect = new QCheckBox(
        QStringLiteral("Reconnect this receive-only profile when the Agent starts"), radioGroup);
    m_autoConnect->setChecked(true);
    radioForm->addRow(QStringLiteral("Radio model"), m_model);
    radioForm->addRow(QStringLiteral("Connection"), m_route);
    radioForm->addRow(QStringLiteral("Baud rate"), m_baud);
    radioForm->addRow(QString(), m_autoConnect);
    auto *radioButtons = new QHBoxLayout;
    auto *refreshDevices = new QPushButton(QStringLiteral("Refresh radios and ports"), radioGroup);
    auto *saveRadio = new QPushButton(QStringLiteral("Save and test connection"), radioGroup);
    auto *clearRadio = new QPushButton(QStringLiteral("Remove profile"), radioGroup);
    radioButtons->addWidget(refreshDevices);
    radioButtons->addWidget(saveRadio);
    radioButtons->addWidget(clearRadio);
    radioForm->addRow(QString(), radioButtons);
    layout->addWidget(radioGroup);

    auto *statusGroup = new QGroupBox(QStringLiteral("Agent status"), root);
    auto *statusLayout = new QVBoxLayout(statusGroup);
    m_status = new QLabel(QStringLiteral("Starting…"), statusGroup);
    m_status->setWordWrap(true);
    m_details = new QPlainTextEdit(statusGroup);
    m_details->setReadOnly(true);
    m_details->setMaximumBlockCount(200);
    m_details->setMinimumHeight(120);
    auto *statusButtons = new QHBoxLayout;
    auto *refreshStatus = new QPushButton(QStringLiteral("Refresh status"), statusGroup);
    auto *startAgent = new QPushButton(QStringLiteral("Start Agent"), statusGroup);
    auto *stopAgent = new QPushButton(QStringLiteral("Stop Agent"), statusGroup);
    statusButtons->addWidget(refreshStatus);
    statusButtons->addStretch();
    statusButtons->addWidget(startAgent);
    statusButtons->addWidget(stopAgent);
    statusLayout->addWidget(m_status);
    statusLayout->addWidget(m_details);
    statusLayout->addLayout(statusButtons);
    layout->addWidget(statusGroup);

    setCentralWidget(root);
    connect(pairButton, &QPushButton::clicked, this, [this] { pair(); });
    connect(unpairButton, &QPushButton::clicked, this, [this] { unpair(); });
    connect(refreshDevices, &QPushButton::clicked, this, [this] { loadDevices(); });
    connect(saveRadio, &QPushButton::clicked, this, [this] { saveRadioProfile(); });
    connect(clearRadio, &QPushButton::clicked, this, [this] { clearRadioProfile(); });
    connect(refreshStatus, &QPushButton::clicked, this, [this] { refreshStatusView(true); });
    connect(startAgent, &QPushButton::clicked, this, [this] { ensureAgentRunning(); });
    connect(stopAgent, &QPushButton::clicked, this, [this] { stopAgentService(true); });

    if (smokeTest) return;
    loadDevices();
    ensureAgentRunning();
    auto *monitor = new QTimer(this);
    monitor->setInterval(5'000);
    connect(monitor, &QTimer::timeout, this, [this] { refreshStatusView(false); });
    monitor->start();
  }

 private:
  QString helperPath() const {
    return QCoreApplication::applicationDirPath() + QStringLiteral("/shackcq-stationd");
  }

  CommandResult run(const QStringList &arguments, int timeout = 30'000) const {
    QProcess process;
    process.setProcessChannelMode(QProcess::MergedChannels);
    process.start(helperPath(), arguments);
    if (!process.waitForStarted(5'000)) return {-1, process.errorString().toUtf8()};
    if (!process.waitForFinished(timeout)) {
      process.kill();
      process.waitForFinished(2'000);
      return {-1, QByteArrayLiteral("The Agent command timed out")};
    }
    return {process.exitCode(), process.readAll()};
  }

  void appendResult(const CommandResult &result) {
    const QString message = QString::fromUtf8(result.output).trimmed();
    if (!message.isEmpty()) m_details->appendPlainText(message);
  }

  void ensureAgentRunning() {
    const CommandResult result = run({QStringLiteral("--status")}, 5'000);
    if (result.exitCode != 0) {
      if (!QProcess::startDetached(helperPath(), {QStringLiteral("--foreground")})) {
        m_status->setText(QStringLiteral("Agent could not start."));
        appendResult({-1, QByteArrayLiteral("Could not launch the bundled Agent service")});
        return;
      }
      m_status->setText(QStringLiteral("Agent is starting in the background."));
      return;
    }
    showStatus(result);
  }

  void refreshStatusView(bool reportFailure) {
    const CommandResult result = run({QStringLiteral("--status")}, 5'000);
    if (result.exitCode != 0) {
      m_status->setText(QStringLiteral("Agent is not running."));
      if (reportFailure) appendResult(result);
      return;
    }
    showStatus(result);
  }

  void showStatus(const CommandResult &result) {
    const QJsonObject envelope = QJsonDocument::fromJson(result.output).object();
    const QJsonObject status = envelope.value(QStringLiteral("result")).toObject();
    const QJsonObject cloud = status.value(QStringLiteral("cloudAgent")).toObject();
    const QJsonObject radio = status.value(QStringLiteral("radio")).toObject();
    const QString cloudState = cloud.value(QStringLiteral("state")).toString(QStringLiteral("available"));
    const QString radioState = radio.value(QStringLiteral("state")).toString(QStringLiteral("DISCONNECTED"));
    m_status->setText(QStringLiteral("Agent running · ShackCQ %1 · Radio %2")
                          .arg(cloudState, radioState));
    m_details->setPlainText(QString::fromUtf8(QJsonDocument(status).toJson(QJsonDocument::Indented)));
  }

  bool stopAgentService(bool report) {
    const CommandResult result = run({QStringLiteral("--stop")}, 8'000);
    if (result.exitCode == 0) {
      m_status->setText(QStringLiteral("Agent stopped."));
      if (report) appendResult(result);
      return true;
    }
    if (report) appendResult(result);
    return false;
  }

  bool prepareConfiguration() {
    const CommandResult status = run({QStringLiteral("--status")}, 5'000);
    if (status.exitCode != 0) return true;
    if (stopAgentService(false)) return true;
    QMessageBox::critical(
        this, QStringLiteral("Agent could not stop safely"),
        QStringLiteral("Configuration was not changed because the running Agent could not confirm a safe stop. "
                       "Check the radio state and try again."));
    return false;
  }

  void restartAfterConfiguration() {
    if (!QProcess::startDetached(helperPath(), {QStringLiteral("--foreground")})) {
      m_status->setText(QStringLiteral("Configuration saved, but the Agent could not restart."));
      return;
    }
    m_status->setText(QStringLiteral("Configuration saved. Agent is restarting…"));
  }

  void pair() {
    const QString code = m_pairingCode->text().trimmed();
    if (code.isEmpty() || m_agentName->text().trimmed().isEmpty()) {
      QMessageBox::warning(this, QStringLiteral("Pairing details required"),
                           QStringLiteral("Enter the one-time pairing code and an Agent name."));
      return;
    }
    if (!prepareConfiguration()) return;
    const CommandResult result = run({QStringLiteral("--pair-with-shackcq"), code,
                                      QStringLiteral("--cloud-origin"), m_origin->text().trimmed(),
                                      QStringLiteral("--agent-name"), m_agentName->text().trimmed()}, 45'000);
    m_pairingCode->clear();
    appendResult(result);
    if (result.exitCode != 0) {
      QMessageBox::critical(this, QStringLiteral("Pairing failed"),
                            QString::fromUtf8(result.output).trimmed());
      restartAfterConfiguration();
      return;
    }
    restartAfterConfiguration();
    QMessageBox::information(
        this, QStringLiteral("Paired"),
        QStringLiteral("This Mac is paired with ShackCQ. The credential is stored in macOS Keychain."));
  }

  void unpair() {
    if (QMessageBox::question(this, QStringLiteral("Unpair this Mac?"),
                              QStringLiteral("The ShackCQ credential will be removed from macOS Keychain.")) !=
        QMessageBox::Yes)
      return;
    if (!prepareConfiguration()) return;
    const CommandResult result = run({QStringLiteral("--unpair-shackcq")}, 15'000);
    appendResult(result);
    restartAfterConfiguration();
    if (result.exitCode != 0)
      QMessageBox::critical(this, QStringLiteral("Unpair failed"),
                            QString::fromUtf8(result.output).trimmed());
  }

  void loadDevices() {
    const QVariant selectedModel = m_model->currentData();
    m_model->clear();
    const CommandResult models = run({QStringLiteral("--list-hamlib-models")}, 15'000);
    const QJsonArray modelRows = QJsonDocument::fromJson(models.output).array();
    for (const QJsonValue &value : modelRows) {
      const QJsonObject model = value.toObject();
      const int id = model.value(QStringLiteral("id")).toInt();
      const QString label = QStringLiteral("%1 %2 (%3)")
                                .arg(model.value(QStringLiteral("manufacturer")).toString(),
                                     model.value(QStringLiteral("model")).toString())
                                .arg(id);
      m_model->addItem(label, id);
    }
    const int previous = m_model->findData(selectedModel);
    if (previous >= 0) m_model->setCurrentIndex(previous);
    if (models.exitCode != 0) appendResult(models);

    const QString selectedRoute = m_route->currentText();
    m_route->clear();
    const CommandResult ports = run({QStringLiteral("--list-serial-ports")}, 10'000);
    const QJsonArray portRows = QJsonDocument::fromJson(ports.output).array();
    for (const QJsonValue &value : portRows) {
      const QJsonObject port = value.toObject();
      const QString route = port.value(QStringLiteral("systemLocation")).toString();
      const QString description = port.value(QStringLiteral("description")).toString();
      m_route->addItem(description.isEmpty() ? route : QStringLiteral("%1 — %2").arg(route, description), route);
    }
    if (!selectedRoute.isEmpty()) m_route->setEditText(selectedRoute);
    if (ports.exitCode != 0) appendResult(ports);
  }

  void saveRadioProfile() {
    if (m_model->currentIndex() < 0 || m_route->currentText().trimmed().isEmpty()) {
      QMessageBox::warning(this, QStringLiteral("Radio details required"),
                           QStringLiteral("Choose a radio model and connection first."));
      return;
    }
    QString route = m_route->currentData().toString();
    if (m_route->lineEdit()->isModified() || route.isEmpty()) route = m_route->currentText().trimmed();
    if (!prepareConfiguration()) return;
    QStringList arguments{QStringLiteral("--configure-hamlib"), m_model->currentData().toString(),
                          QStringLiteral("--radio-route"), route,
                          QStringLiteral("--radio-baud"), m_baud->currentText(),
                          QStringLiteral("--test-radio-connection")};
    if (!m_autoConnect->isChecked()) arguments << QStringLiteral("--no-radio-autoconnect");
    const CommandResult result = run(arguments, 30'000);
    appendResult(result);
    restartAfterConfiguration();
    if (result.exitCode == 0) {
      QMessageBox::information(this, QStringLiteral("Radio connected"),
                               QStringLiteral("The receive-only radio profile was saved and verified."));
    } else {
      QMessageBox::critical(this, QStringLiteral("Radio connection failed"),
                            QString::fromUtf8(result.output).trimmed());
    }
  }

  void clearRadioProfile() {
    if (QMessageBox::question(this, QStringLiteral("Remove radio profile?"),
                              QStringLiteral("The saved radio connection will be removed. No radio command will be sent.")) !=
        QMessageBox::Yes)
      return;
    if (!prepareConfiguration()) return;
    const CommandResult result = run({QStringLiteral("--clear-hamlib-profile")}, 15'000);
    appendResult(result);
    restartAfterConfiguration();
    if (result.exitCode != 0)
      QMessageBox::critical(this, QStringLiteral("Could not remove profile"),
                            QString::fromUtf8(result.output).trimmed());
  }

  QLineEdit *m_origin{};
  QLineEdit *m_agentName{};
  QLineEdit *m_pairingCode{};
  QComboBox *m_model{};
  QComboBox *m_route{};
  QComboBox *m_baud{};
  QCheckBox *m_autoConnect{};
  QLabel *m_status{};
  QPlainTextEdit *m_details{};
};
}

int main(int argc, char **argv) {
  QApplication application(argc, argv);
  QCoreApplication::setApplicationName(QStringLiteral("ShackCQ Agent"));
  QCoreApplication::setApplicationVersion(QStringLiteral("1.0.0"));
  const bool smokeTest = QCoreApplication::arguments().contains(QStringLiteral("--ui-smoke"));
  const bool preview = QCoreApplication::arguments().contains(QStringLiteral("--ui-preview"));
  AgentWindow window(smokeTest || preview);
  window.show();
  if (smokeTest) QTimer::singleShot(500, &application, &QCoreApplication::quit);
  return application.exec();
}
