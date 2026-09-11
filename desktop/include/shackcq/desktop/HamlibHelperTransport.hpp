#pragma once

#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QProcess>

namespace shackcq::desktop {

class HamlibHelperTransport final : public QObject {
  Q_OBJECT
public:
  explicit HamlibHelperTransport(QObject *parent = nullptr);
  ~HamlibHelperTransport() override;

  bool open(int modelId, const QString &route, int baudRate,
            QJsonObject *description = nullptr);
  QJsonObject mutate(const QString &action, const QJsonObject &parameters);
  QJsonObject snapshot();
  QJsonObject setPtt(bool enabled);
  bool priorityStop();
  void close();

  bool quarantined() const { return m_quarantined; }
  bool operationActive() const { return m_operationActive; }
  quint64 epoch() const { return m_epoch; }
  void setProgramForTest(const QString &program,
                         const QStringList &arguments = {});
  void setTimeoutsForTest(int operationMillis, int stopMillis);

signals:
  void sanitizedError(QString message);
  void unsafeOwnershipLost();

private:
  QJsonObject request(const QString &operation, const QJsonObject &parameters,
                      int timeoutMillis, bool allowWhileQuarantined = false);
  bool startProcess();
  bool emergencyStop();
  void consumeOutput();
  void quarantine(const QString &reason);

  QProcess m_process;
  QByteArray m_output;
  QHash<QString, QJsonObject> m_responses;
  QString m_program;
  QStringList m_arguments;
  QString m_route;
  int m_modelId{};
  int m_baudRate{};
  quint64 m_nextRequest{};
  quint64 m_epoch{};
  // Some physical radios need several seconds for Hamlib's initial serial
  // identification exchange. Keep STOP independently bounded below.
  int m_operationTimeoutMillis{8'000};
  int m_stopTimeoutMillis{1'000};
  bool m_operationActive{};
  bool m_stopInProgress{};
  bool m_intentionalTermination{};
  bool m_lossNotifiedForProcess{};
  bool m_routeConfigured{};
  bool m_quarantined{true};
};

} // namespace shackcq::desktop
