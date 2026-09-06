#pragma once

#include <QObject>
#include <QSerialPort>
#include <QTcpSocket>
#include <QTimer>

namespace shackcq::desktop {

class DesktopRotatorController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString state READ state NOTIFY snapshotChanged)
    Q_PROPERTY(double azimuth READ azimuth NOTIFY snapshotChanged)
    Q_PROPERTY(double elevation READ elevation NOTIFY snapshotChanged)
    Q_PROPERTY(double preparedAzimuth READ preparedAzimuth NOTIFY preparedChanged)
    Q_PROPERTY(double preparedElevation READ preparedElevation NOTIFY preparedChanged)
    Q_PROPERTY(bool automationArmed READ automationArmed CONSTANT)
    Q_PROPERTY(QString protocol READ protocol NOTIFY snapshotChanged)
public:
    explicit DesktopRotatorController(QObject *parent = nullptr);
    ~DesktopRotatorController() override;
    QString state() const { return m_state; }
    double azimuth() const { return m_azimuth; }
    double elevation() const { return m_elevation; }
    double preparedAzimuth() const { return m_preparedAzimuth; }
    double preparedElevation() const { return m_preparedElevation; }
    bool automationArmed() const { return false; }
    QString protocol() const { return m_protocol; }
    Q_INVOKABLE bool connectRotator(int modelId, const QString &port, int baudRate);
    Q_INVOKABLE bool connectNative(const QString &protocol, const QString &route,
                                   int baudRate);
    Q_INVOKABLE bool configureSafety(double minimumAzimuth,
                                     double maximumAzimuth,
                                     double minimumElevation,
                                     double maximumElevation,
                                     const QVariantList &forbiddenSectors);
    Q_INVOKABLE void disconnectRotator();
    Q_INVOKABLE bool prepareTarget(double azimuth, double elevation);
    Q_INVOKABLE bool confirmMove();
    Q_INVOKABLE void stop();
    Q_INVOKABLE bool park();
    QVariantMap configuration() const;
    bool restoreConfiguration(const QVariantMap &section,
                              QString *error = nullptr);
signals:
    void snapshotChanged();
    void preparedChanged();
    void confirmationRequired(double azimuth, double elevation);
    void error(QString message);
private:
    void poll();
    bool writeFrame(const QByteArray &frame);
    QByteArray frame(const QString &operation, double azimuth = 0,
                     double elevation = 0) const;
    void consume(const QByteArray &bytes);
    bool crossesForbiddenSector(double from, double to) const;
    void *m_rotator{};
    QSerialPort m_serial;
    QTcpSocket m_tcp;
    QByteArray m_buffer;
    QTimer m_poll;
    QString m_state{"Disconnected / automation disarmed"};
    double m_azimuth{};
    double m_elevation{};
    double m_preparedAzimuth{};
    double m_preparedElevation{};
    bool m_targetPrepared{};
    QString m_protocol{"none"};
    double m_minAzimuth{0};
    double m_maxAzimuth{450};
    double m_minElevation{-10};
    double m_maxElevation{180};
    QVariantList m_forbiddenSectors;
};

} // namespace shackcq::desktop
