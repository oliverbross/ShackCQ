// SPDX-License-Identifier: GPL-3.0-only
#include <QApplication>
#include <QCheckBox>
#include <QComboBox>
#include <QCoreApplication>
#include <QDesktopServices>
#include <QFont>
#include <QFormLayout>
#include <QFrame>
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
#include <QScrollArea>
#include <QSizePolicy>
#include <QTimer>
#include <QVBoxLayout>
#include <QWidget>

namespace {
struct CommandResult {
  int exitCode{-1};
  QByteArray output;
  QByteArray error;
};

class AgentWindow final : public QMainWindow {
 public:
  explicit AgentWindow(bool smokeTest = false) {
    setWindowTitle(QStringLiteral("ShackCQ Agent"));
    resize(720, 720);
    setMinimumSize(620, 600);
    setUnifiedTitleAndToolBarOnMac(true);

    auto *root = new QWidget(this);
    root->setObjectName(QStringLiteral("agentRoot"));
    auto *layout = new QVBoxLayout(root);
    layout->setContentsMargins(28, 24, 28, 28);
    layout->setSpacing(18);
    auto *heading = new QLabel(QStringLiteral("Connect this Mac to ShackCQ"), root);
    heading->setObjectName(QStringLiteral("pageTitle"));
    QFont headingFont = heading->font();
    headingFont.setPointSize(24);
    headingFont.setWeight(QFont::DemiBold);
    heading->setFont(headingFont);
    layout->addWidget(heading);
    auto *intro = new QLabel(
        QStringLiteral("Pair your account, then choose the radio connected to this Mac. "
                       "The Agent starts in receive-only mode and never enables transmit during setup."), root);
    intro->setObjectName(QStringLiteral("secondaryText"));
    intro->setWordWrap(true);
    layout->addWidget(intro);

    auto *cloudGroup = new QFrame(root);
    cloudGroup->setObjectName(QStringLiteral("settingsCard"));
    auto *cloudLayout = new QVBoxLayout(cloudGroup);
    cloudLayout->setContentsMargins(18, 16, 18, 18);
    cloudLayout->setSpacing(12);
    auto *cloudTitle = new QLabel(QStringLiteral("ShackCQ account"), cloudGroup);
    cloudTitle->setObjectName(QStringLiteral("sectionTitle"));
    cloudLayout->addWidget(cloudTitle);
    auto *cloudHelp = new QLabel(
        QStringLiteral("Create a one-time code in ShackCQ, then paste it below. Codes expire after 10 minutes."),
        cloudGroup);
    cloudHelp->setObjectName(QStringLiteral("secondaryText"));
    cloudHelp->setWordWrap(true);
    cloudLayout->addWidget(cloudHelp);
    auto *cloudForm = new QFormLayout;
    cloudForm->setHorizontalSpacing(16);
    cloudForm->setVerticalSpacing(10);
    cloudForm->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
    m_origin = new QLineEdit(QStringLiteral("https://shackcq.com"), cloudGroup);
    m_agentName = new QLineEdit(QHostInfo::localHostName(), cloudGroup);
    m_pairingCode = new QLineEdit(cloudGroup);
    m_pairingCode->setPlaceholderText(QStringLiteral("XXXX XXXX XXXX"));
    m_pairingCode->setClearButtonEnabled(true);
    m_pairingCode->setAccessibleName(QStringLiteral("One-time ShackCQ pairing code"));
    cloudForm->addRow(QStringLiteral("ShackCQ address"), m_origin);
    cloudForm->addRow(QStringLiteral("Agent name"), m_agentName);
    cloudForm->addRow(QStringLiteral("Pairing code"), m_pairingCode);
    cloudLayout->addLayout(cloudForm);
    auto *cloudButtons = new QHBoxLayout;
    cloudButtons->setSpacing(8);
    auto *openPairing = new QPushButton(QStringLiteral("Get pairing code…"), cloudGroup);
    openPairing->setObjectName(QStringLiteral("linkButton"));
    auto *pairButton = new QPushButton(QStringLiteral("Pair this Mac"), cloudGroup);
    pairButton->setObjectName(QStringLiteral("primaryButton"));
    pairButton->setDefault(true);
    auto *unpairButton = new QPushButton(QStringLiteral("Unpair"), cloudGroup);
    cloudButtons->addWidget(openPairing);
    cloudButtons->addStretch();
    cloudButtons->addWidget(unpairButton);
    cloudButtons->addWidget(pairButton);
    cloudLayout->addLayout(cloudButtons);
    layout->addWidget(cloudGroup);

    auto *radioGroup = new QFrame(root);
    radioGroup->setObjectName(QStringLiteral("settingsCard"));
    auto *radioLayout = new QVBoxLayout(radioGroup);
    radioLayout->setContentsMargins(18, 16, 18, 18);
    radioLayout->setSpacing(12);
    auto *radioTitle = new QLabel(QStringLiteral("Radio connection"), radioGroup);
    radioTitle->setObjectName(QStringLiteral("sectionTitle"));
    radioLayout->addWidget(radioTitle);
    auto *radioHelp = new QLabel(
        QStringLiteral("USB serial radios appear automatically. You can also enter a supported network route."),
        radioGroup);
    radioHelp->setObjectName(QStringLiteral("secondaryText"));
    radioHelp->setWordWrap(true);
    radioLayout->addWidget(radioHelp);
    auto *radioForm = new QFormLayout;
    radioForm->setHorizontalSpacing(16);
    radioForm->setVerticalSpacing(10);
    radioForm->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
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
    radioLayout->addLayout(radioForm);
    auto *radioButtons = new QHBoxLayout;
    radioButtons->setSpacing(8);
    auto *refreshDevices = new QPushButton(QStringLiteral("Refresh devices"), radioGroup);
    auto *saveRadio = new QPushButton(QStringLiteral("Save and test"), radioGroup);
    saveRadio->setObjectName(QStringLiteral("primaryButton"));
    auto *clearRadio = new QPushButton(QStringLiteral("Remove profile"), radioGroup);
    radioButtons->addWidget(refreshDevices);
    radioButtons->addStretch();
    radioButtons->addWidget(clearRadio);
    radioButtons->addWidget(saveRadio);
    radioLayout->addLayout(radioButtons);
    layout->addWidget(radioGroup);

    auto *statusGroup = new QFrame(root);
    statusGroup->setObjectName(QStringLiteral("settingsCard"));
    auto *statusLayout = new QVBoxLayout(statusGroup);
    statusLayout->setContentsMargins(18, 16, 18, 18);
    statusLayout->setSpacing(10);
    auto *statusTitle = new QLabel(QStringLiteral("Agent status"), statusGroup);
    statusTitle->setObjectName(QStringLiteral("sectionTitle"));
    statusLayout->addWidget(statusTitle);
    m_status = new QLabel(QStringLiteral("Starting Agent…"), statusGroup);
    m_status->setObjectName(QStringLiteral("statusText"));
    m_status->setWordWrap(true);
    m_details = new QPlainTextEdit(statusGroup);
    m_details->setReadOnly(true);
    m_details->setMaximumBlockCount(200);
    m_details->setMinimumHeight(92);
    m_details->setMaximumHeight(140);
    m_details->setPlaceholderText(QStringLiteral("Connection details will appear here."));
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

    layout->addStretch();
    auto *scroll = new QScrollArea(this);
    scroll->setFrameShape(QFrame::NoFrame);
    scroll->setWidgetResizable(true);
    scroll->setWidget(root);
    setCentralWidget(scroll);
    const bool dark = palette().color(QPalette::Window).lightness() < 128;
    const QString windowColor = dark ? QStringLiteral("#1c1c1e") : QStringLiteral("#f5f5f7");
    const QString cardColor = dark ? QStringLiteral("#2c2c2e") : QStringLiteral("#ffffff");
    const QString fieldColor = dark ? QStringLiteral("#1f1f21") : QStringLiteral("#ffffff");
    const QString borderColor = dark ? QStringLiteral("#4a4a4e") : QStringLiteral("#d1d1d6");
    const QString textColor = dark ? QStringLiteral("#f5f5f7") : QStringLiteral("#1d1d1f");
    const QString secondaryColor = dark ? QStringLiteral("#b7b7bd") : QStringLiteral("#5f5f66");
    root->setStyleSheet(QStringLiteral(
        "QWidget#agentRoot { background: %1; color: %5; }"
        "QFrame#settingsCard { background: %2; border: 1px solid %4; border-radius: 12px; }"
        "QLabel#sectionTitle { font-size: 15px; font-weight: 600; border: none; }"
        "QLabel#secondaryText { color: %6; border: none; }"
        "QLabel#statusText { font-size: 14px; font-weight: 600; border: none; }"
        "QLineEdit, QComboBox { min-height: 30px; padding: 0 8px; background: %3; color: %5; "
        "border: 1px solid %4; border-radius: 7px; selection-background-color: #0a84ff; }"
        "QLineEdit:focus, QComboBox:focus { border: 2px solid #0a84ff; }"
        "QPushButton { min-height: 30px; padding: 0 14px; background: %2; color: %5; "
        "border: 1px solid %4; border-radius: 7px; }"
        "QPushButton:hover { border-color: #0a84ff; }"
        "QPushButton:pressed { background: %3; }"
        "QPushButton#primaryButton { background: #0a84ff; color: white; border: 1px solid #0a84ff; font-weight: 600; }"
        "QPushButton#primaryButton:pressed { background: #006edb; }"
        "QPushButton#linkButton { color: #0a84ff; border-color: transparent; background: transparent; padding-left: 0; }"
        "QPlainTextEdit { background: %3; color: %5; border: 1px solid %4; border-radius: 8px; padding: 8px; }"
        "QCheckBox { spacing: 8px; border: none; }"
        "QToolTip { background: %2; color: %5; border: 1px solid %4; }")
        .arg(windowColor, cardColor, fieldColor, borderColor, textColor, secondaryColor));
    connect(pairButton, &QPushButton::clicked, this, [this] { pair(); });
    connect(unpairButton, &QPushButton::clicked, this, [this] { unpair(); });
    connect(openPairing, &QPushButton::clicked, this, [] {
      QDesktopServices::openUrl(QUrl(QStringLiteral("https://shackcq.com/agents")));
    });
    connect(refreshDevices, &QPushButton::clicked, this, [this] { loadDevices(); });
    connect(saveRadio, &QPushButton::clicked, this, [this] { saveRadioProfile(); });
    connect(clearRadio, &QPushButton::clicked, this, [this] { clearRadioProfile(); });
    connect(refreshStatus, &QPushButton::clicked, this, [this] { refreshStatusView(true); });
    connect(startAgent, &QPushButton::clicked, this, [this] { ensureAgentRunning(); });
    connect(stopAgent, &QPushButton::clicked, this, [this] { stopAgentService(true); });

    if (smokeTest) {
      loadDevices();
      if (m_model->count() == 0)
        qFatal("The bundled Agent helper returned no Hamlib radio models");
      return;
    }
    loadDevices();
    ensureAgentRunning();
    auto *monitor = new QTimer(this);
    monitor->setInterval(5'000);
    connect(monitor, &QTimer::timeout, this, [this] { refreshStatusView(false); });
    monitor->start();
    auto *deviceMonitor = new QTimer(this);
    deviceMonitor->setInterval(3'000);
    connect(deviceMonitor, &QTimer::timeout, this, [this] { loadSerialPorts(false); });
    deviceMonitor->start();
  }

 private:
  QString helperPath() const {
    return QCoreApplication::applicationDirPath() + QStringLiteral("/shackcq-stationd");
  }

  CommandResult run(const QStringList &arguments, int timeout = 30'000) const {
    QProcess process;
    process.setProcessChannelMode(QProcess::SeparateChannels);
    process.start(helperPath(), arguments);
    if (!process.waitForStarted(5'000)) return {-1, {}, process.errorString().toUtf8()};
    if (!process.waitForFinished(timeout)) {
      process.kill();
      process.waitForFinished(2'000);
      return {-1, {}, QByteArrayLiteral("The Agent command timed out")};
    }
    return {process.exitCode(), process.readAllStandardOutput(),
            process.readAllStandardError()};
  }

  void appendResult(const CommandResult &result) {
    const QString message = resultText(result);
    if (!message.isEmpty()) m_details->appendPlainText(message);
  }

  QString resultText(const CommandResult &result) const {
    QString message = QString::fromUtf8(result.output).trimmed();
    const QString error = QString::fromUtf8(result.error).trimmed();
    if (!error.isEmpty()) {
      if (!message.isEmpty()) message += QLatin1Char('\n');
      message += error;
    }
    return message;
  }

  void ensureAgentRunning() {
    const CommandResult result = run({QStringLiteral("--status")}, 8'000);
    if (result.exitCode != 0) {
      if (!QProcess::startDetached(helperPath(), {QStringLiteral("--foreground")})) {
        m_status->setText(QStringLiteral("Agent could not start."));
        appendResult({-1, {}, QByteArrayLiteral("Could not launch the bundled Agent service")});
        return;
      }
      m_status->setText(QStringLiteral("Agent is starting in the background."));
      return;
    }
    showStatus(result);
  }

  void refreshStatusView(bool reportFailure) {
    const CommandResult result = run({QStringLiteral("--status")}, 8'000);
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
    const QString cloudDetail = cloud.value(QStringLiteral("detail")).toString();
    const QString radioError = radio.value(QStringLiteral("lastSanitizedError")).toString();
    QStringList details{
        QStringLiteral("ShackCQ: %1").arg(cloudDetail.isEmpty() ? cloudState : cloudDetail),
        QStringLiteral("Radio: %1").arg(radioState)};
    if (!radioError.isEmpty()) details << QStringLiteral("Radio detail: %1").arg(radioError);
    m_details->setPlainText(details.join(QLatin1Char('\n')));
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
                            resultText(result));
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

    loadSerialPorts(true);
  }

  void loadSerialPorts(bool reportFailure) {
    const QString selectedRoute = m_route->currentText();
    m_route->clear();
    const CommandResult ports = run({QStringLiteral("--list-serial-ports")}, 10'000);
    const QJsonArray portRows = QJsonDocument::fromJson(ports.output).array();
    for (const QJsonValue &value : portRows) {
      const QJsonObject port = value.toObject();
      const QString route = port.value(QStringLiteral("systemLocation")).toString();
      const QString description = port.value(QStringLiteral("description")).toString();
      const QString lowered = route.toLower();
      if (!route.startsWith(QStringLiteral("/dev/cu.")) ||
          lowered.contains(QStringLiteral("bluetooth")) ||
          lowered.contains(QStringLiteral("debug")) ||
          lowered.contains(QStringLiteral("wlan")))
        continue;
      m_route->addItem(description.isEmpty() ? route : QStringLiteral("%1 — %2").arg(route, description), route);
    }
    const int previous = m_route->findData(selectedRoute);
    if (previous >= 0)
      m_route->setCurrentIndex(previous);
    else if (!selectedRoute.isEmpty())
      m_route->setEditText(selectedRoute);
    else if (m_route->count() > 0)
      m_route->setCurrentIndex(0);
    if (reportFailure && ports.exitCode != 0) appendResult(ports);
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
                            resultText(result));
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
                            resultText(result));
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
  QCoreApplication::setApplicationVersion(QStringLiteral("1.0.1"));
  const bool smokeTest = QCoreApplication::arguments().contains(QStringLiteral("--ui-smoke"));
  const bool preview = QCoreApplication::arguments().contains(QStringLiteral("--ui-preview"));
  AgentWindow window(smokeTest || preview);
  window.show();
  if (smokeTest) QTimer::singleShot(500, &application, &QCoreApplication::quit);
  return application.exec();
}
