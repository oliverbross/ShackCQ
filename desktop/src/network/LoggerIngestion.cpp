// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/LoggerIngestion.hpp"

#include "kx3/wsjtx_protocol.hpp"

#include <algorithm>
#include <variant>

#include <QCryptographicHash>
#include <QDateTime>
#include <QFile>
#include <QFileInfo>
#include <QHostAddress>
#include <QJsonDocument>
#include <QSaveFile>
#include <QTimeZone>
#include <QUdpSocket>
#include <QXmlStreamReader>

namespace shackcq::desktop {
namespace {
constexpr qsizetype MaxDatagramBytes = 64 * 1024;
constexpr int MaxPendingEvents = 5000;
constexpr qint64 MaxPendingBytes = 32 * 1024 * 1024;
constexpr qint64 MaxAgeSeconds = 30LL * 24 * 60 * 60;

QString digest(const QByteArray &value) {
  return QString::fromLatin1(
      QCryptographicHash::hash(value, QCryptographicHash::Sha256).toHex());
}
QString safeId(const QByteArray &value) {
  return QStringLiteral("le-") + digest(value).left(48);
}
QString iso(qint64 milliseconds) {
  return QDateTime::fromMSecsSinceEpoch(milliseconds, QTimeZone::UTC)
      .toString(Qt::ISODateWithMs);
}
QString identityUtc(const QString &value) {
  const QDateTime parsed = QDateTime::fromString(value, Qt::ISODate);
  return parsed.isValid() ? parsed.toUTC().toString(Qt::ISODate) : QString{};
}
QString adifValue(const QString &raw, const QString &wanted) {
  qsizetype at = 0;
  while ((at = raw.indexOf('<', at)) >= 0) {
    const qsizetype close = raw.indexOf('>', at + 1);
    if (close < 0 || close - at > 80)
      return {};
    const QStringList bits = raw.mid(at + 1, close - at - 1).split(':');
    if (bits.size() < 2) {
      at = close + 1;
      continue;
    }
    bool ok = false;
    const int length = bits[1].toInt(&ok);
    if (!ok || length < 0 || length > 4096 || close + 1 + length > raw.size())
      return {};
    if (bits[0].compare(wanted, Qt::CaseInsensitive) == 0)
      return raw.mid(close + 1, length).trimmed();
    at = close + 1 + length;
  }
  return {};
}
QString adifUtc(const QString &date, const QString &time) {
  const QString padded = time.leftJustified(6, '0');
  const QDateTime parsed = QDateTime::fromString(
      date + padded.left(6), QStringLiteral("yyyyMMddHHmmss"));
  const QDateTime value(parsed.date(), parsed.time(), QTimeZone::UTC);
  return value.isValid() ? value.toUTC().toString(Qt::ISODateWithMs)
                         : QString{};
}
QJsonObject baseContact(const QString &call, const QString &start) {
  return {{"callsign", call.trimmed().toUpper()}, {"contactStartUtc", start}};
}
} // namespace

struct LoggerIngestion::Profile {
  QString id;
  QString source;
  QString instanceId;
  QString destinationAuthority;
  QString stationProfileId;
  int authorityRevision{};
  int mappingRevision{};
  quint16 port{};
  QUdpSocket *socket{};
  QString state{"PAUSED"};
  QString error;
  QDateTime lastPacket;
};

LoggerIngestion::LoggerIngestion(DesktopCredentialVault *vault,
                                 QString journalPath, QObject *parent)
    : QObject(parent), m_vault(vault), m_journalPath(std::move(journalPath)) {
  loadJournal();
  loadProfiles();
}

LoggerIngestion::~LoggerIngestion() { qDeleteAll(m_profiles); }

QJsonObject LoggerIngestion::applyProfile(const QJsonObject &value) {
  const QString id = value.value("id").toString();
  const QString source = value.value("source").toString();
  const QString instance = value.value("instanceId").toString();
  const QString station = value.value("stationProfileId").toString();
  const QString authority = value.value("destinationAuthority").toString();
  const int revision = value.value("authorityRevision").toInt();
  const int port = value.value("loopbackPort").toInt();
  const bool enabled = value.value("enabled").toBool(false);
  auto result = [&](bool ok, const QString &code) {
    return QJsonObject{{"ok", ok}, {"code", code}};
  };
  if (id.isEmpty() || id.size() > 160 || instance.isEmpty() ||
      instance.size() > 128 || station.isEmpty() || station.size() > 160 ||
      (source != "WSJTX" && source != "N1MM") ||
      (authority != "WEB_LOCAL" && authority != "WAVELOG") || revision < 1 ||
      port < 1024 || port > 65535)
    return result(false, "LOGGER_PROFILE_INVALID");
  Profile *profile = m_profiles.value(id, nullptr);
  if (!profile) {
    profile = new Profile;
    profile->id = id;
    m_profiles.insert(id, profile);
  }
  for (Profile *other : std::as_const(m_profiles))
    if (enabled && other != profile && other->socket &&
        other->port == quint16(port))
      return result(false, "LOGGER_PORT_IN_USE");
  QUdpSocket *replacement = nullptr;
  if (enabled && (!profile->socket || profile->port != quint16(port))) {
    replacement = new QUdpSocket;
    if (!replacement->bind(QHostAddress::LocalHost, quint16(port),
                           QUdpSocket::DontShareAddress)) {
      delete replacement;
      setError(profile, "LOGGER_BIND_FAILED");
      return result(false, "LOGGER_BIND_FAILED");
    }
  }
  if (!enabled && profile->socket) {
    profile->socket->close();
    delete profile->socket;
    profile->socket = nullptr;
  } else if (replacement) {
    if (profile->socket) {
      profile->socket->close();
      delete profile->socket;
    }
    profile->socket = replacement;
    connect(profile->socket, &QUdpSocket::readyRead, this,
            [this, profile] { receive(profile); });
  }
  profile->source = source;
  profile->instanceId = instance;
  profile->stationProfileId = station;
  profile->destinationAuthority = authority;
  profile->authorityRevision = revision;
  profile->mappingRevision = value.value("mappingRevision").toInt();
  profile->port = quint16(port);
  profile->error.clear();
  profile->state = enabled ? "STARTING" : "PAUSED";
  m_profileConfigs.insert(id, value);
  if (!saveProfiles())
    return result(false, "LOGGER_PROFILE_SAVE_FAILED");
  if (!enabled)
    return result(true, "LOGGER_PAUSED");
  profile->state = "LISTENING";
  return result(true, "LOGGER_LISTENING");
}

void LoggerIngestion::receive(Profile *profile) {
  while (profile->socket && profile->socket->hasPendingDatagrams()) {
    if (profile->socket->pendingDatagramSize() <= 0 ||
        profile->socket->pendingDatagramSize() > MaxDatagramBytes) {
      profile->socket->readDatagram(nullptr, 0);
      setError(profile, "LOGGER_PACKET_TOO_LARGE");
      continue;
    }
    QByteArray packet(profile->socket->pendingDatagramSize(),
                      Qt::Uninitialized);
    QHostAddress sender;
    quint16 senderPort{};
    if (profile->socket->readDatagram(packet.data(), packet.size(), &sender,
                                      &senderPort) != packet.size() ||
        !sender.isLoopback()) {
      setError(profile, "LOGGER_NON_LOOPBACK_REJECTED");
      continue;
    }
    profile->lastPacket = QDateTime::currentDateTimeUtc();
    const QJsonObject event = profile->source == "WSJTX"
                                  ? parseWsjt(profile, packet)
                                  : parseN1mm(profile, packet);
    if (!event.isEmpty() && storeEvent(event))
      emit eventsReady();
  }
}

QJsonObject LoggerIngestion::parseWsjt(Profile *profile,
                                       const QByteArray &packet) const {
  kx3::wsjtx::ParseError error{};
  const auto parsed = kx3::wsjtx::parse_datagram(
      reinterpret_cast<const std::uint8_t *>(packet.constData()),
      std::size_t(packet.size()), &error);
  if (!parsed)
    return {};
  QJsonObject contact;
  QString sourceKey;
  if (const auto *logged =
          std::get_if<kx3::wsjtx::QsoLogged>(&parsed->payload)) {
    const QString call =
        QString::fromStdString(logged->dx_call).trimmed().toUpper();
    const QString start = iso(logged->on_utc_milliseconds);
    contact = baseContact(call, start);
    contact.insert("contactEndUtc", iso(logged->off_utc_milliseconds));
    if (logged->tx_frequency_hz)
      contact.insert("frequencyHz", QJsonValue::fromVariant(
                                        qulonglong(logged->tx_frequency_hz)));
    contact.insert("mode", QString::fromStdString(logged->mode).toUpper());
    if (!logged->dx_grid.empty())
      contact.insert("grid", QString::fromStdString(logged->dx_grid).toUpper());
    if (!logged->report_sent.empty())
      contact.insert("rstSent", QString::fromStdString(logged->report_sent));
    if (!logged->report_received.empty())
      contact.insert("rstReceived",
                     QString::fromStdString(logged->report_received));
    bool powerOk = false;
    const double power =
        QString::fromStdString(logged->tx_power).toDouble(&powerOk);
    if (powerOk && power >= 0 && power <= 100000)
      contact.insert("powerWatts", power);
    sourceKey = call + "|" + identityUtc(start);
  } else if (const auto *logged =
                 std::get_if<kx3::wsjtx::LoggedAdif>(&parsed->payload)) {
    const QString raw = QString::fromStdString(logged->raw);
    const QString call = QString::fromStdString(logged->call).toUpper();
    const QString start = adifUtc(QString::fromStdString(logged->qso_date),
                                  QString::fromStdString(logged->time_on));
    if (start.isEmpty())
      return {};
    contact = baseContact(call, start);
    bool frequencyOk = false;
    const double mhz =
        QString::fromStdString(logged->frequency_mhz).toDouble(&frequencyOk);
    if (frequencyOk && mhz >= 0 && mhz <= 10500)
      contact.insert("frequencyHz", qint64(mhz * 1000000.0 + 0.5));
    if (!logged->band.empty())
      contact.insert("band", QString::fromStdString(logged->band).toLower());
    if (!logged->mode.empty())
      contact.insert("mode", QString::fromStdString(logged->mode).toUpper());
    if (!logged->submode.empty())
      contact.insert("submode",
                     QString::fromStdString(logged->submode).toUpper());
    if (!logged->gridsquare.empty())
      contact.insert("grid",
                     QString::fromStdString(logged->gridsquare).toUpper());
    QJsonObject adif;
    for (const char *field : {"RST_SENT", "RST_RCVD", "TIME_OFF", "TX_PWR"}) {
      const QString v = adifValue(raw, field);
      if (!v.isEmpty())
        adif.insert(field, v);
    }
    if (!adif.isEmpty())
      contact.insert("adif", adif);
    const QString ownCall = adifValue(raw, "STATION_CALLSIGN").toUpper();
    if (!ownCall.isEmpty())
      contact.insert("ownCallsign", ownCall);
    sourceKey = call + "|" + identityUtc(start);
  } else
    return {};
  const QString sourceId =
      safeId((profile->instanceId + "|" + sourceKey).toUtf8());
  const QByteArray canonical =
      QJsonDocument(contact).toJson(QJsonDocument::Compact);
  const QString payloadDigest = digest(canonical);
  QJsonObject event{
      {"eventId",
       safeId((profile->id + "|" + sourceId + "|1|" + payloadDigest).toUtf8())},
      {"payloadDigest", payloadDigest},
      {"profileId", profile->id},
      {"source", "WSJTX"},
      {"instanceId", profile->instanceId},
      {"sourceContactId", sourceId},
      {"sourceRevision", 1},
      {"kind", "create"},
      {"capturedUtc",
       QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs)},
      {"destinationAuthority", profile->destinationAuthority},
      {"authorityRevision", profile->authorityRevision},
      {"stationProfileId", profile->stationProfileId},
      {"contact", contact}};
  if (profile->mappingRevision > 0)
    event.insert("mappingRevision", profile->mappingRevision);
  return event;
}

QJsonObject LoggerIngestion::parseN1mm(Profile *profile,
                                       const QByteArray &packet) const {
  QXmlStreamReader xml(packet);
  QString root;
  QHash<QString, QString> fields;
  int depth = 0;
  while (!xml.atEnd()) {
    const auto token = xml.readNext();
    if (token == QXmlStreamReader::DTD ||
        token == QXmlStreamReader::EntityReference ||
        token == QXmlStreamReader::ProcessingInstruction)
      return {};
    if (token == QXmlStreamReader::StartElement) {
      if (++depth > 4)
        return {};
      const QString name = xml.name().toString().toLower();
      if (root.isEmpty()) {
        root = name;
        if (root != "contactinfo" && root != "contactreplace" &&
            root != "contactdelete")
          return {};
      } else {
        if (fields.contains(name) || fields.size() >= 80)
          return {};
        fields.insert(name, xml.readElementText(
                                   QXmlStreamReader::ErrorOnUnexpectedElement)
                                .trimmed());
        --depth;
      }
    } else if (token == QXmlStreamReader::EndElement)
      --depth;
  }
  if (xml.hasError())
    return {};
  const QString call = fields.value("call").toUpper();
  QString start = fields.value("timestamp");
  QDateTime dt = QDateTime::fromString(start, Qt::ISODate);
  if (!dt.isValid())
    dt = QDateTime::fromString(start, QStringLiteral("yyyy-MM-dd HH:mm:ss"));
  if (call.isEmpty() || !dt.isValid())
    return {};
  dt = QDateTime(dt.date(), dt.time(), QTimeZone::UTC);
  start = dt.toString(Qt::ISODateWithMs);
  QJsonObject contact = baseContact(call, start);
  bool ok = false;
  const qint64 frequency10 = fields.value("freq").toLongLong(&ok);
  if (ok && frequency10 >= 0 && frequency10 <= 1050000000LL)
    contact.insert("frequencyHz", frequency10 * 10);
  auto put = [&](const char *out, const char *in, bool upper = true) {
    QString v = fields.value(QString::fromLatin1(in));
    if (!v.isEmpty())
      contact.insert(out, upper ? v.toUpper() : v);
  };
  put("band", "band", false);
  put("mode", "mode");
  put("submode", "submode");
  put("grid", "gridsquare");
  put("rstSent", "snt", false);
  put("rstReceived", "rcv", false);
  put("exchangeSent", "sntnr", false);
  put("exchangeReceived", "rcvnr", false);
  put("ownCallsign", "mycall");
  QString rawId = fields.value("id");
  if (rawId.isEmpty())
    rawId = call + "|" + start;
  const QString sourceId = safeId((profile->instanceId + "|" + rawId).toUtf8());
  const QString kind = root == "contactinfo"      ? "create"
                       : root == "contactreplace" ? "update"
                                                  : "delete";
  const int sourceRevision = kind == "create" ? 1 : 2;
  const QByteArray canonical =
      QJsonDocument(contact).toJson(QJsonDocument::Compact);
  const QString payloadDigest = digest(canonical + kind.toUtf8());
  QJsonObject event{
      {"eventId", safeId((profile->id + "|" + sourceId + "|" +
                          QString::number(sourceRevision) + "|" + payloadDigest)
                             .toUtf8())},
      {"payloadDigest", payloadDigest},
      {"profileId", profile->id},
      {"source", "N1MM"},
      {"instanceId", profile->instanceId},
      {"sourceContactId", sourceId},
      {"sourceRevision", sourceRevision},
      {"kind", kind},
      {"capturedUtc",
       QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs)},
      {"destinationAuthority", profile->destinationAuthority},
      {"authorityRevision", profile->authorityRevision},
      {"stationProfileId", profile->stationProfileId},
      {"contact", contact}};
  if (profile->mappingRevision > 0)
    event.insert("mappingRevision", profile->mappingRevision);
  return event;
}

bool LoggerIngestion::storeEvent(const QJsonObject &event) {
  const QDateTime cutoff =
      QDateTime::currentDateTimeUtc().addSecs(-MaxAgeSeconds);
  QJsonArray kept;
  qint64 bytes = 0;
  for (const QJsonValue &item : m_journal) {
    const QJsonObject row = item.toObject();
    if (QDateTime::fromString(row.value("capturedUtc").toString(),
                              Qt::ISODate) >= cutoff) {
      kept.append(row);
      bytes += row.value("bytes").toInteger();
    } else if (m_vault)
      m_vault->remove(row.value("alias").toString());
  }
  m_journal = kept;
  const QByteArray encoded =
      QJsonDocument(event).toJson(QJsonDocument::Compact);
  const QString eventId = event.value("eventId").toString();
  for (const QJsonValue &item : m_journal)
    if (item.toObject().value("eventId").toString() == eventId)
      return true;
  if (m_journal.size() >= MaxPendingEvents ||
      bytes + encoded.size() > MaxPendingBytes) {
    m_lastError = "LOGGER_JOURNAL_FULL";
    return false;
  }
  const QString alias = QStringLiteral("shackcq-logger-event-") + eventId;
  QString error;
  if (!m_vault || !m_vault->write(alias, "ShackCQ pending logger event",
                                  QString::fromUtf8(encoded), &error)) {
    m_lastError = "LOGGER_VAULT_UNAVAILABLE";
    return false;
  }
  m_journal.append(QJsonObject{{"eventId", eventId},
                               {"alias", alias},
                               {"capturedUtc", event.value("capturedUtc")},
                               {"bytes", encoded.size()}});
  if (!saveJournal()) {
    m_vault->remove(alias);
    m_journal.removeLast();
    m_lastError = "LOGGER_JOURNAL_WRITE_FAILED";
    return false;
  }
  m_lastError.clear();
  return true;
}

QJsonArray LoggerIngestion::pendingEvents(int maximum) const {
  QJsonArray result;
  for (const QJsonValue &item : m_journal) {
    if (result.size() >= std::clamp(maximum, 1, 32))
      break;
    const auto secret =
        m_vault ? m_vault->read(item.toObject().value("alias").toString())
                : std::nullopt;
    if (secret) {
      QJsonParseError error;
      const QJsonObject event =
          QJsonDocument::fromJson(secret->toUtf8(), &error).object();
      if (error.error == QJsonParseError::NoError && !event.isEmpty())
        result.append(event);
    }
  }
  return result;
}
void LoggerIngestion::acknowledge(const QJsonArray &receipts) {
  QSet<QString> accepted;
  for (const QJsonValue &item : receipts) {
    const QJsonObject row = item.toObject();
    if (row.value("accepted").toBool())
      accepted.insert(row.value("eventId").toString());
  }
  if (accepted.isEmpty())
    return;
  QJsonArray kept;
  for (const QJsonValue &item : m_journal) {
    const QJsonObject row = item.toObject();
    if (accepted.contains(row.value("eventId").toString())) {
      if (m_vault)
        m_vault->remove(row.value("alias").toString());
    } else
      kept.append(row);
  }
  m_journal = kept;
  saveJournal();
}
bool LoggerIngestion::loadJournal() {
  QFile file(m_journalPath);
  if (!file.exists())
    return true;
  if (!file.open(QIODevice::ReadOnly) || file.size() > 1024 * 1024)
    return false;
  QJsonParseError error;
  const QJsonArray rows =
      QJsonDocument::fromJson(file.readAll(), &error).array();
  if (error.error != QJsonParseError::NoError || rows.size() > MaxPendingEvents)
    return false;
  m_journal = rows;
  return true;
}
bool LoggerIngestion::saveJournal() const {
  QSaveFile file(m_journalPath);
  if (!file.open(QIODevice::WriteOnly))
    return false;
  file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
  if (file.write(QJsonDocument(m_journal).toJson(QJsonDocument::Compact)) < 0)
    return false;
  return file.commit();
}
bool LoggerIngestion::loadProfiles() {
  QFile file(m_journalPath + ".profiles");
  if (!file.exists())
    return true;
  if (!file.open(QIODevice::ReadOnly) || file.size() > 256 * 1024)
    return false;
  QJsonParseError error;
  const QJsonObject rows =
      QJsonDocument::fromJson(file.readAll(), &error).object();
  if (error.error != QJsonParseError::NoError || rows.size() > 64)
    return false;
  for (const QJsonValue &value : rows)
    if (value.isObject())
      applyProfile(value.toObject());
  return true;
}
bool LoggerIngestion::saveProfiles() const {
  QSaveFile file(m_journalPath + ".profiles");
  if (!file.open(QIODevice::WriteOnly))
    return false;
  file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
  if (file.write(
          QJsonDocument(m_profileConfigs).toJson(QJsonDocument::Compact)) < 0)
    return false;
  return file.commit();
}
void LoggerIngestion::setError(Profile *profile, const QString &code) {
  profile->state = "ERROR";
  profile->error = code;
  m_lastError = code;
}
QVariantMap LoggerIngestion::health() const {
  return {{"profiles", m_profiles.size()},
          {"pendingEvents", m_journal.size()},
          {"lastError", m_lastError},
          {"authority", "LOOPBACK_RECEIVE_ONLY"},
          {"radioAccess", false}};
}

} // namespace shackcq::desktop
