#include "shackcq/desktop/DesktopRotatorController.hpp"

#include <QRegularExpression>
#include <QSet>
#include <QUrl>
#include <algorithm>
#include <cmath>

#ifdef SHACKCQ_HAVE_HAMLIB
#include <hamlib/rotator.h>
#endif

namespace shackcq::desktop {

DesktopRotatorController::DesktopRotatorController(QObject *parent)
    : QObject(parent) {
  m_poll.setInterval(500);
  connect(&m_poll, &QTimer::timeout, this, &DesktopRotatorController::poll);
  connect(&m_serial, &QSerialPort::readyRead, this,
          [this] { consume(m_serial.readAll()); });
  connect(&m_tcp, &QTcpSocket::readyRead, this,
          [this] { consume(m_tcp.readAll()); });
}

DesktopRotatorController::~DesktopRotatorController() { disconnectRotator(); }

bool DesktopRotatorController::connectRotator(int modelId, const QString &port,
                                              int baudRate) {
  disconnectRotator();
#ifdef SHACKCQ_HAVE_HAMLIB
  if (port.trimmed().isEmpty()) {
    emit error("An explicit rotator route is required");
    return false;
  }
  ROT *rot = rot_init(modelId);
  if (!rot) {
    emit error("Hamlib rejected the rotator model");
    return false;
  }
  auto set = [rot](const char *name, const QString &value) {
    const token_t token = rot_token_lookup(rot, name);
    return token != RIG_CONF_END &&
           rot_set_conf(rot, token, value.toUtf8().constData()) == RIG_OK;
  };
  if (!set("rot_pathname", port)) {
    rot_cleanup(rot);
    emit error("Hamlib rejected the rotator route");
    return false;
  }
  if (baudRate > 0)
    set("serial_speed", QString::number(baudRate));
  const int code = rot_open(rot);
  if (code != RIG_OK) {
    const QString message = QStringLiteral("Hamlib rotator connect failed: %1")
                                .arg(QString::fromLatin1(rigerror(code)));
    rot_cleanup(rot);
    emit error(message);
    return false;
  }
  m_rotator = rot;
  m_protocol = "HAMLIB";
  m_state = "Connected / automation disarmed / PROMPT movement";
  m_poll.start();
  poll();
  emit snapshotChanged();
  return true;
#else
  Q_UNUSED(modelId);
  Q_UNUSED(port);
  Q_UNUSED(baudRate);
  emit error("This build was compiled without pinned Hamlib 4.7.2");
  return false;
#endif
}

bool DesktopRotatorController::connectNative(const QString &protocol,
                                             const QString &route,
                                             int baudRate) {
  disconnectRotator();
  const QString id = protocol.trimmed().toUpper();
  if (!QSet<QString>{"GS232", "EASYCOMM", "ROTCTLD"}.contains(id) ||
      route.trimmed().isEmpty()) {
    emit error("Supported native protocol and explicit route are required");
    return false;
  }
  bool opened = false;
  const QUrl endpoint(route);
  if (endpoint.isValid() && endpoint.scheme() == "tcp") {
    if (endpoint.host().isEmpty() || endpoint.port() < 1 ||
        endpoint.port() > 65535) {
      emit error("Native rotator TCP route must be tcp://host:port");
      return false;
    }
    m_tcp.connectToHost(endpoint.host(), quint16(endpoint.port()));
    opened = m_tcp.waitForConnected(1500);
  } else {
    if (id == "ROTCTLD") {
      emit error("rotctld requires an explicit tcp://host:port route");
      return false;
    }
    m_serial.setPortName(route.trimmed());
    m_serial.setBaudRate(std::clamp(baudRate, 1200, 921600));
    m_serial.setDataBits(QSerialPort::Data8);
    m_serial.setParity(QSerialPort::NoParity);
    m_serial.setStopBits(QSerialPort::OneStop);
    m_serial.setFlowControl(QSerialPort::NoFlowControl);
    opened = m_serial.open(QIODevice::ReadWrite);
  }
  if (!opened) {
    const QString detail =
        endpoint.scheme() == "tcp" ? m_tcp.errorString() : m_serial.errorString();
    emit error(QStringLiteral("Native rotator connect failed: %1").arg(detail));
    disconnectRotator();
    return false;
  }
  m_protocol = id;
  m_state = "Connected / native / automation disarmed / PROMPT movement";
  m_poll.start();
  poll();
  emit snapshotChanged();
  return true;
}

bool DesktopRotatorController::configureSafety(
    double minimumAzimuth, double maximumAzimuth, double minimumElevation,
    double maximumElevation, const QVariantList &forbiddenSectors) {
  if (minimumAzimuth < -180 || maximumAzimuth > 720 ||
      maximumAzimuth <= minimumAzimuth || minimumElevation < -90 ||
      maximumElevation > 180 || maximumElevation <= minimumElevation ||
      forbiddenSectors.size() > 64)
    return false;
  for (const QVariant &entry : forbiddenSectors) {
    const QVariantMap sector = entry.toMap();
    const double start = sector.value("startDeg").toDouble();
    const double end = sector.value("endDeg").toDouble();
    if (start < 0 || start >= 360 || end < 0 || end >= 360)
      return false;
  }
  m_minAzimuth = minimumAzimuth;
  m_maxAzimuth = maximumAzimuth;
  m_minElevation = minimumElevation;
  m_maxElevation = maximumElevation;
  m_forbiddenSectors = forbiddenSectors;
  return true;
}

void DesktopRotatorController::disconnectRotator() {
  m_poll.stop();
  if (m_serial.isOpen())
    m_serial.close();
  if (m_tcp.state() != QAbstractSocket::UnconnectedState)
    m_tcp.abort();
  m_buffer.clear();
#ifdef SHACKCQ_HAVE_HAMLIB
  if (m_rotator) {
    auto *rot = static_cast<ROT *>(m_rotator);
    rot_close(rot);
    rot_cleanup(rot);
    m_rotator = nullptr;
  }
#endif
  m_protocol = "none";
  m_state = "Disconnected / automation disarmed";
  m_targetPrepared = false;
  emit snapshotChanged();
  emit preparedChanged();
}

bool DesktopRotatorController::crossesForbiddenSector(double from,
                                                      double to) const {
  const int steps = std::max(1, int(std::ceil(std::abs(to - from))));
  for (int step = 0; step <= steps; ++step) {
    double heading = std::fmod(from + (to - from) * step / steps, 360.0);
    if (heading < 0)
      heading += 360.0;
    for (const QVariant &entry : m_forbiddenSectors) {
      const QVariantMap sector = entry.toMap();
      const double start = sector.value("startDeg").toDouble();
      const double end = sector.value("endDeg").toDouble();
      const bool inside =
          start <= end ? heading >= start && heading <= end
                       : heading >= start || heading <= end;
      if (inside)
        return true;
    }
  }
  return false;
}

bool DesktopRotatorController::prepareTarget(double azimuth, double elevation) {
  if (azimuth < m_minAzimuth || azimuth > m_maxAzimuth ||
      elevation < m_minElevation || elevation > m_maxElevation ||
      crossesForbiddenSector(m_azimuth, azimuth)) {
    emit error("Target or direct path violates configured rotator safety bounds");
    return false;
  }
  m_preparedAzimuth = azimuth;
  m_preparedElevation = elevation;
  m_targetPrepared = true;
  emit preparedChanged();
  emit confirmationRequired(azimuth, elevation);
  return true;
}

QByteArray DesktopRotatorController::frame(const QString &operation,
                                           double azimuth,
                                           double elevation) const {
  const QString op = operation.toLower();
  if (m_protocol == "GS232") {
    if (op == "query")
      return "C2\r";
    if (op == "stop")
      return "S\r";
    if (op == "move")
      return QStringLiteral("W%1 %2\r")
          .arg(qRound(azimuth), 3, 10, QLatin1Char('0'))
          .arg(qRound(elevation), 3, 10, QLatin1Char('0'))
          .toLatin1();
  } else if (m_protocol == "EASYCOMM") {
    if (op == "query")
      return "AZ EL\n";
    if (op == "stop")
      return "SA SE\n";
    if (op == "move")
      return QStringLiteral("AZ%1 EL%2\n")
          .arg(azimuth, 0, 'f', 1)
          .arg(elevation, 0, 'f', 1)
          .toLatin1();
  } else if (m_protocol == "ROTCTLD") {
    if (op == "query")
      return "p\n";
    if (op == "stop")
      return "S\n";
    if (op == "move")
      return QStringLiteral("P %1 %2\n")
          .arg(azimuth, 0, 'f', 1)
          .arg(elevation, 0, 'f', 1)
          .toLatin1();
  }
  return {};
}

bool DesktopRotatorController::writeFrame(const QByteArray &value) {
  if (value.isEmpty() || value.size() > 128)
    return false;
  if (m_serial.isOpen())
    return m_serial.write(value) == value.size();
  if (m_tcp.state() == QAbstractSocket::ConnectedState)
    return m_tcp.write(value) == value.size();
  return false;
}

bool DesktopRotatorController::confirmMove() {
  if (!m_targetPrepared)
    return false;
  m_targetPrepared = false;
  emit preparedChanged();
  if (m_protocol != "none" && m_protocol != "HAMLIB")
    return writeFrame(frame("move", m_preparedAzimuth, m_preparedElevation));
#ifdef SHACKCQ_HAVE_HAMLIB
  if (!m_rotator)
    return false;
  const int code =
      rot_set_position(static_cast<ROT *>(m_rotator),
                       azimuth_t(m_preparedAzimuth),
                       elevation_t(m_preparedElevation));
  if (code != RIG_OK) {
    emit error(QString::fromLatin1(rigerror(code)));
    return false;
  }
  return true;
#else
  return false;
#endif
}

void DesktopRotatorController::stop() {
  m_targetPrepared = false;
  emit preparedChanged();
  if (m_protocol != "none" && m_protocol != "HAMLIB") {
    writeFrame(frame("stop"));
    return;
  }
#ifdef SHACKCQ_HAVE_HAMLIB
  if (m_rotator) {
    const int code = rot_stop(static_cast<ROT *>(m_rotator));
    if (code != RIG_OK)
      emit error(QString::fromLatin1(rigerror(code)));
  }
#endif
}

bool DesktopRotatorController::park() {
  if (m_protocol != "none" && m_protocol != "HAMLIB")
    return false;
#ifdef SHACKCQ_HAVE_HAMLIB
  if (!m_rotator)
    return false;
  const int code = rot_park(static_cast<ROT *>(m_rotator));
  if (code != RIG_OK) {
    emit error(QString::fromLatin1(rigerror(code)));
    return false;
  }
  return true;
#else
  return false;
#endif
}

void DesktopRotatorController::poll() {
  if (m_protocol != "none" && m_protocol != "HAMLIB") {
    writeFrame(frame("query"));
    return;
  }
#ifdef SHACKCQ_HAVE_HAMLIB
  if (!m_rotator)
    return;
  azimuth_t az = 0;
  elevation_t el = 0;
  if (rot_get_position(static_cast<ROT *>(m_rotator), &az, &el) == RIG_OK) {
    m_azimuth = az;
    m_elevation = el;
    emit snapshotChanged();
  }
#endif
}

void DesktopRotatorController::consume(const QByteArray &bytes) {
  for (const char byte : bytes) {
    const uchar value = uchar(byte);
    if (value < 0x20 && byte != '\r' && byte != '\n')
      continue;
    if (m_buffer.size() >= 512) {
      m_buffer.clear();
      emit error("Native rotator response exceeded 512-byte bound");
      return;
    }
    m_buffer.append(byte);
  }
  const QString text = QString::fromLatin1(m_buffer);
  static const QRegularExpression labelled(
      QStringLiteral("(?:AZ[= ]?|[+-]0?)(-?\\d{1,3}(?:\\.\\d+)?)"
                     "[^0-9-]+(?:EL[= ]?|[+-]0?)(-?\\d{1,3}(?:\\.\\d+)?)"),
      QRegularExpression::CaseInsensitiveOption);
  auto match = labelled.match(text);
  if (!match.hasMatch() && m_protocol == "ROTCTLD") {
    static const QRegularExpression twoLines(
        QStringLiteral("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[\\r\\n]+"
                       "(-?\\d+(?:\\.\\d+)?)\\s*[\\r\\n]+"));
    match = twoLines.match(text);
  }
  if (!match.hasMatch()) {
    if (m_buffer.size() > 256 || text.count('\n') > 2)
      m_buffer.clear();
    return;
  }
  const double azimuth = match.captured(1).toDouble();
  const double elevation = match.captured(2).toDouble();
  m_buffer.remove(0, match.capturedEnd());
  if (azimuth < m_minAzimuth || azimuth > m_maxAzimuth ||
      elevation < m_minElevation || elevation > m_maxElevation) {
    emit error("Rotator returned telemetry outside configured bounds");
    return;
  }
  m_azimuth = azimuth;
  m_elevation = elevation;
  emit snapshotChanged();
}

QVariantMap DesktopRotatorController::configuration() const {
  return {{"schemaVersion", 1},
          {"protocol", m_protocol == "none" ? QString{} : m_protocol},
          {"minimumAzimuth", m_minAzimuth},
          {"maximumAzimuth", m_maxAzimuth},
          {"minimumElevation", m_minElevation},
          {"maximumElevation", m_maxElevation},
          {"forbiddenSectors", m_forbiddenSectors},
          {"automationArmed", false},
          {"connected", false},
          {"pendingTarget", QVariant{}}};
}

bool DesktopRotatorController::restoreConfiguration(const QVariantMap &section,
                                                    QString *error) {
  if (section.value("schemaVersion", 1).toInt() > 1) {
    if (error)
      *error = "rotatorProfiles schema is newer than supported schema 1";
    return false;
  }
  if (!configureSafety(section.value("minimumAzimuth", 0).toDouble(),
                       section.value("maximumAzimuth", 450).toDouble(),
                       section.value("minimumElevation", -10).toDouble(),
                       section.value("maximumElevation", 180).toDouble(),
                       section.value("forbiddenSectors").toList())) {
    if (error)
      *error = "Invalid rotator safety configuration";
    return false;
  }
  disconnectRotator();
  return true;
}

} // namespace shackcq::desktop
