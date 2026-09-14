#include "shackcq/desktop/QsoDatabase.hpp"

#include <QDir>
#include <QCryptographicHash>
#include <QDate>
#include <QFileInfo>
#include <QJsonDocument>
#include <QSqlError>
#include <QSqlQuery>
#include <QUuid>
#include <QRegularExpression>
#include <QSet>
#include <QTimeZone>
#include <cmath>
#include <limits>

namespace shackcq::desktop {
namespace {

QString sqlError(const QSqlQuery &query) {
    return query.lastError().text().left(500);
}

QsoRecord fromQuery(const QSqlQuery &q) {
    QsoRecord r;
    r.id = q.value("id").toString(); r.callsign = q.value("callsign").toString();
    r.frequencyHz = q.value("frequency_hz").toLongLong(); r.frequencyRxHz = q.value("frequency_rx_hz").toLongLong();
    r.band = q.value("band").toString(); r.bandRx = q.value("band_rx").toString();
    r.mode = q.value("mode").toString(); r.submode = q.value("submode").toString();
    r.rstSent = q.value("rst_sent").toString(); r.rstReceived = q.value("rst_received").toString();
    r.grid = q.value("grid").toString(); r.comment = q.value("comment").toString();
    r.stationProfileId = q.value("station_profile_id").toString(); r.stationCallsign = q.value("station_callsign").toString();
    r.operatorCallsign = q.value("operator_callsign").toString(); r.dxcc = q.value("dxcc").toString();
    r.country = q.value("country").toString(); r.cqZone = q.value("cq_zone").toString(); r.ituZone = q.value("itu_zone").toString();
    r.contestId = q.value("contest_id").toString(); r.satelliteName = q.value("satellite_name").toString();
    r.satelliteMode = q.value("satellite_mode").toString(); r.propagationMode = q.value("propagation_mode").toString();
    r.antennaPath = q.value("antenna_path").toString(); r.txPower = q.isNull("tx_power") ? std::numeric_limits<double>::quiet_NaN() : q.value("tx_power").toDouble(); r.antenna = q.value("antenna").toString();
    r.potaRef = q.value("pota_ref").toString();
    r.sotaRef = q.value("sota_ref").toString(); r.iota = q.value("iota").toString(); r.wwffRef = q.value("wwff_ref").toString();
    r.qslManager = q.value("qsl_manager").toString(); r.qslMessage = q.value("qsl_message").toString();
    r.qslSent = q.value("qsl_sent").toString(); r.qslReceived = q.value("qsl_received").toString();
    r.qslSentDate = q.value("qsl_sent_date").toString(); r.qslReceivedDate = q.value("qsl_received_date").toString();
    r.qslSentMethod = q.value("qsl_sent_method").toString(); r.qslReceivedMethod = q.value("qsl_received_method").toString();
    r.lotwReceived = q.value("lotw_received").toString();
    r.eqslReceived = q.value("eqsl_received").toString(); r.qrzReceived = q.value("qrz_received").toString();
    r.provenance = q.value("provenance").toString(); r.remoteId = q.value("remote_id").toString();
    r.createdAt = q.value("created_at").toLongLong(); r.updatedAt = q.value("updated_at").toLongLong();
    r.deleted = q.value("deleted").toBool();
    r.extraAdif = QJsonDocument::fromJson(q.value("extra_adif_json").toByteArray()).object();
    return r;
}

} // namespace

QsoDatabase::QsoDatabase(QString path, QObject *parent)
    : QObject(parent), m_path(std::move(path)), m_connectionName(QStringLiteral("shackcq-desktop-%1").arg(QUuid::createUuid().toString(QUuid::WithoutBraces))) {}

QsoDatabase::~QsoDatabase() {
    if (m_database.isValid()) m_database.close();
    m_database = {};
    QSqlDatabase::removeDatabase(m_connectionName);
}

bool QsoDatabase::open(QString *error) {
    QDir().mkpath(QFileInfo(m_path).absolutePath());
    m_database = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), m_connectionName);
    m_database.setDatabaseName(m_path);
    if (!m_database.open()) {
        if (error) *error = m_database.lastError().text().left(500);
        return false;
    }
    QSqlQuery pragma(m_database);
    pragma.exec(QStringLiteral("PRAGMA foreign_keys=ON"));
    pragma.exec(QStringLiteral("PRAGMA journal_mode=WAL"));
    pragma.exec(QStringLiteral("PRAGMA synchronous=NORMAL"));
    pragma.exec(QStringLiteral("PRAGMA busy_timeout=5000"));
    return migrate(error);
}

bool QsoDatabase::execute(const QString &sql, QString *error) const {
    QSqlQuery query(m_database);
    if (query.exec(sql)) return true;
    if (error) *error = sqlError(query);
    return false;
}

bool QsoDatabase::migrate(QString *error) {
    QSqlQuery versionQuery(m_database);
    if (!versionQuery.exec(QStringLiteral("PRAGMA user_version")) || !versionQuery.next()) {
        if (error) *error = sqlError(versionQuery);
        return false;
    }
    const int sourceVersion = versionQuery.value(0).toInt();
    if (sourceVersion > SchemaVersion) {
        if (error) *error = QStringLiteral("Database schema %1 is newer than supported schema %2").arg(sourceVersion).arg(SchemaVersion);
        return false;
    }
    if (!m_database.transaction()) { if (error) *error = m_database.lastError().text(); return false; }
    const QStringList statements = {
        QStringLiteral("CREATE TABLE IF NOT EXISTS desktop_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)"),
        QStringLiteral("CREATE TABLE IF NOT EXISTS qso("
                       "id TEXT PRIMARY KEY,callsign TEXT NOT NULL,frequency_hz INTEGER NOT NULL,frequency_rx_hz INTEGER NOT NULL DEFAULT 0,"
                       "band TEXT NOT NULL,band_rx TEXT NOT NULL DEFAULT '',mode TEXT NOT NULL,submode TEXT NOT NULL DEFAULT '',"
                       "rst_sent TEXT NOT NULL DEFAULT '59',rst_received TEXT NOT NULL DEFAULT '59',grid TEXT NOT NULL DEFAULT '',comment TEXT NOT NULL DEFAULT '',"
                       "station_profile_id TEXT NOT NULL DEFAULT '',station_callsign TEXT NOT NULL DEFAULT '',operator_callsign TEXT NOT NULL DEFAULT '',"
                       "dxcc TEXT NOT NULL DEFAULT '',country TEXT NOT NULL DEFAULT '',cq_zone TEXT NOT NULL DEFAULT '',itu_zone TEXT NOT NULL DEFAULT '',"
                       "contest_id TEXT NOT NULL DEFAULT '',satellite_name TEXT NOT NULL DEFAULT '',satellite_mode TEXT NOT NULL DEFAULT '',"
                       "propagation_mode TEXT NOT NULL DEFAULT '',antenna_path TEXT NOT NULL DEFAULT '',tx_power REAL,antenna TEXT NOT NULL DEFAULT '',"
                       "pota_ref TEXT NOT NULL DEFAULT '',sota_ref TEXT NOT NULL DEFAULT '',iota TEXT NOT NULL DEFAULT '',wwff_ref TEXT NOT NULL DEFAULT '',"
                       "qsl_manager TEXT NOT NULL DEFAULT '',qsl_message TEXT NOT NULL DEFAULT '',qsl_sent TEXT NOT NULL DEFAULT '',qsl_received TEXT NOT NULL DEFAULT 'N',"
                       "qsl_sent_date TEXT NOT NULL DEFAULT '',qsl_received_date TEXT NOT NULL DEFAULT '',qsl_sent_method TEXT NOT NULL DEFAULT '',qsl_received_method TEXT NOT NULL DEFAULT '',"
                       "lotw_received TEXT NOT NULL DEFAULT 'N',eqsl_received TEXT NOT NULL DEFAULT 'N',qrz_received TEXT NOT NULL DEFAULT 'N',"
                       "provenance TEXT NOT NULL DEFAULT 'local',remote_id TEXT NOT NULL DEFAULT '',extra_adif_json TEXT NOT NULL DEFAULT '{}',"
                       "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)"),
        QStringLiteral("CREATE TABLE IF NOT EXISTS qso_projection("
                       "qso_id TEXT PRIMARY KEY REFERENCES qso(id) ON DELETE CASCADE,callsign_norm TEXT NOT NULL,frequency_hz INTEGER NOT NULL,"
                       "band TEXT NOT NULL,mode TEXT NOT NULL,dxcc TEXT NOT NULL,grid TEXT NOT NULL,cq_zone TEXT NOT NULL,itu_zone TEXT NOT NULL,"
                       "wpx_prefix TEXT NOT NULL,portable_ref TEXT NOT NULL,confirmed INTEGER NOT NULL,station_profile_id TEXT NOT NULL,"
                       "provenance TEXT NOT NULL,created_at INTEGER NOT NULL,deleted INTEGER NOT NULL)"),
        QStringLiteral("CREATE TABLE IF NOT EXISTS qso_tombstone(qso_id TEXT PRIMARY KEY,remote_id TEXT NOT NULL,canonical_hash TEXT NOT NULL,deleted_at INTEGER NOT NULL,acknowledged_at INTEGER)"),
        QStringLiteral("CREATE INDEX IF NOT EXISTS qso_page_idx ON qso(deleted,created_at DESC,id DESC)"),
        QStringLiteral("CREATE INDEX IF NOT EXISTS qso_station_page_idx ON qso(station_profile_id,deleted,created_at DESC,id DESC)"),
        QStringLiteral("CREATE INDEX IF NOT EXISTS projection_call_idx ON qso_projection(callsign_norm,band,mode,confirmed)"),
        QStringLiteral("CREATE INDEX IF NOT EXISTS projection_entity_idx ON qso_projection(dxcc,band,mode,confirmed)"),
        QStringLiteral("CREATE INDEX IF NOT EXISTS projection_grid_idx ON qso_projection(grid,band,mode)"),
        QStringLiteral("INSERT OR IGNORE INTO desktop_meta(key,value) VALUES('database_revision','0')")
    };
    for (const auto &statement : statements) {
        if (!execute(statement, error)) { m_database.rollback(); return false; }
    }
    QSet<QString> columns;
    QSqlQuery columnQuery(m_database);
    if (!columnQuery.exec(QStringLiteral("PRAGMA table_info(qso)"))) { if (error) *error = sqlError(columnQuery); m_database.rollback(); return false; }
    while (columnQuery.next()) columns.insert(columnQuery.value(1).toString());
    const QMap<QString, QString> advancedColumns{
        {"propagation_mode", "TEXT NOT NULL DEFAULT ''"}, {"antenna_path", "TEXT NOT NULL DEFAULT ''"},
        {"tx_power", "REAL"}, {"antenna", "TEXT NOT NULL DEFAULT ''"},
        {"qsl_manager", "TEXT NOT NULL DEFAULT ''"}, {"qsl_message", "TEXT NOT NULL DEFAULT ''"},
        {"qsl_sent", "TEXT NOT NULL DEFAULT ''"}, {"qsl_sent_date", "TEXT NOT NULL DEFAULT ''"},
        {"qsl_received_date", "TEXT NOT NULL DEFAULT ''"}, {"qsl_sent_method", "TEXT NOT NULL DEFAULT ''"},
        {"qsl_received_method", "TEXT NOT NULL DEFAULT ''"},
    };
    for (auto it = advancedColumns.cbegin(); it != advancedColumns.cend(); ++it) {
        if (!columns.contains(it.key()) && !execute(QStringLiteral("ALTER TABLE qso ADD COLUMN %1 %2").arg(it.key(), it.value()), error)) {
            m_database.rollback(); return false;
        }
    }
    if (sourceVersion < SchemaVersion && !execute(QStringLiteral("UPDATE qso SET propagation_mode='SAT' WHERE propagation_mode='' AND (satellite_name<>'' OR satellite_mode<>'')"), error)) {
        m_database.rollback(); return false;
    }
    if (!execute(QStringLiteral("PRAGMA user_version=17"), error)) { m_database.rollback(); return false; }
    if (!m_database.commit()) { if (error) *error = m_database.lastError().text(); return false; }
    return true;
}

quint64 QsoDatabase::revision() const {
    QSqlQuery q(m_database);
    q.exec(QStringLiteral("SELECT value FROM desktop_meta WHERE key='database_revision'"));
    return q.next() ? q.value(0).toULongLong() : 0;
}

bool QsoDatabase::save(const QsoRecord &input, QString *error) {
    QsoRecord r = input;
    QString *textFields[] = {
        &r.callsign, &r.band, &r.bandRx, &r.mode, &r.submode, &r.rstSent,
        &r.rstReceived, &r.grid, &r.comment, &r.stationProfileId,
        &r.stationCallsign, &r.operatorCallsign, &r.dxcc, &r.country,
        &r.cqZone, &r.ituZone, &r.contestId, &r.satelliteName,
        &r.satelliteMode, &r.propagationMode, &r.antennaPath, &r.antenna,
        &r.potaRef, &r.sotaRef, &r.iota, &r.wwffRef, &r.qslManager, &r.qslMessage,
        &r.qslSent, &r.qslReceived, &r.qslSentDate, &r.qslReceivedDate,
        &r.qslSentMethod, &r.qslReceivedMethod, &r.lotwReceived, &r.eqslReceived, &r.qrzReceived,
        &r.provenance, &r.remoteId,
    };
    for (QString *field : textFields) {
        if (field->isNull()) *field = QStringLiteral("");
    }
    r.callsign = normalizedCallsign(r.callsign);
    r.propagationMode = r.propagationMode.trimmed().toUpper();
    r.antennaPath = r.antennaPath.trimmed().toUpper();
    r.qslSent = r.qslSent.trimmed().toUpper();
    r.qslReceived = r.qslReceived.trimmed().toUpper();
    r.qslSentMethod = r.qslSentMethod.trimmed().toUpper();
    r.qslReceivedMethod = r.qslReceivedMethod.trimmed().toUpper();
    r.potaRef = r.potaRef.trimmed().toUpper(); r.sotaRef = r.sotaRef.trimmed().toUpper();
    r.wwffRef = r.wwffRef.trimmed().toUpper(); r.iota = r.iota.trimmed().toUpper();
    r.satelliteName = r.satelliteName.trimmed().toUpper(); r.satelliteMode = r.satelliteMode.trimmed().toUpper();
    r.antenna = r.antenna.trimmed(); r.qslManager = r.qslManager.trimmed().toUpper(); r.qslMessage = r.qslMessage.trimmed();
    r.qslSentDate = r.qslSentDate.trimmed(); r.qslReceivedDate = r.qslReceivedDate.trimmed();
    for (QString *field : textFields) if (field->isNull()) *field = QStringLiteral("");
    if (r.callsign.isEmpty() || r.frequencyHz <= 0 || r.mode.trimmed().isEmpty()) {
        if (error) *error = QStringLiteral("Callsign, positive frequency, and mode are required");
        return false;
    }
    if ((!std::isnan(r.txPower) && (!std::isfinite(r.txPower) || r.txPower < 0 || r.txPower > 100000)) ||
        (!r.antennaPath.isEmpty() && !QStringList{"S","L","G","O"}.contains(r.antennaPath))) {
        if (error) *error = QStringLiteral("Invalid TX power or antenna path");
        return false;
    }
    if ((!r.qslSent.isEmpty() && !QStringList{"Y","N","Q","R","I"}.contains(r.qslSent)) ||
        !QStringList{"Y","N","R","I","V"}.contains(r.qslReceived) ||
        (!r.qslSentMethod.isEmpty() && !QStringList{"B","D","E","M"}.contains(r.qslSentMethod)) ||
        (!r.qslReceivedMethod.isEmpty() && !QStringList{"B","D","E","M"}.contains(r.qslReceivedMethod))) {
        if (error) *error = QStringLiteral("Invalid QSL status or method");
        return false;
    }
    const auto validDate = [](const QString &value) {
        if (value.isEmpty()) return true;
        const QDate date = QDate::fromString(value, Qt::ISODate);
        return date.isValid() && date.toString(Qt::ISODate) == value;
    };
    if (!validDate(r.qslSentDate) || !validDate(r.qslReceivedDate) ||
        (!r.qslSentDate.isEmpty() && !QStringList{"Y","Q","I"}.contains(r.qslSent)) ||
        (!r.qslReceivedDate.isEmpty() && !QStringList{"Y","I","V"}.contains(r.qslReceived))) {
        if (error) *error = QStringLiteral("Invalid QSL date or status/date combination");
        return false;
    }
    static const QSet<QString> propagationModes{"AS","AUR","AUE","BS","ECH","EME","ES","F2","FAI","INTERNET","ION","IRL","MS","RPT","RS","SAT","TEP","TR"};
    const bool importedUnknownPropagation = (r.provenance == QStringLiteral("import") || r.provenance == QStringLiteral("remote")) &&
                                            !r.propagationMode.isEmpty() && !propagationModes.contains(r.propagationMode);
    if ((!r.satelliteName.isEmpty() || !r.satelliteMode.isEmpty()) && r.propagationMode != QStringLiteral("SAT") && !importedUnknownPropagation) {
        if (error) *error = QStringLiteral("Satellite name and mode require propagation mode SAT");
        return false;
    }
    const QRegularExpression control(QStringLiteral("[\\x00-\\x1f\\x7f]"));
    const auto invalidText = [&](const QString &value, int maximum) { return value.size() > maximum || value.contains(control); };
    if ((!r.propagationMode.isEmpty() && !propagationModes.contains(r.propagationMode) && !importedUnknownPropagation) || invalidText(r.satelliteName,80) ||
        invalidText(r.satelliteMode,40) || invalidText(r.antenna,160) || invalidText(r.qslManager,80) || invalidText(r.qslMessage,512)) {
        if (error) *error = QStringLiteral("Invalid advanced QSO text or propagation mode");
        return false;
    }
    const QRegularExpression pota(QStringLiteral("^[A-Z0-9]{1,4}-\\d{4,5}(?:@[A-Z0-9]{2,6}(?:-[A-Z0-9]{1,3})?)?$"));
    const QRegularExpression sota(QStringLiteral("^[A-Z0-9]{1,4}/[A-Z0-9]{1,4}-\\d{3}$"));
    const QRegularExpression wwff(QStringLiteral("^[A-Z0-9]{1,4}FF-\\d{4}$"));
    const QRegularExpression iota(QStringLiteral("^[A-Z]{2}-\\d{3}$"));
    const auto normalizeReferences=[](QString &value,const QRegularExpression &pattern){QStringList refs;for(const QString &part:value.split(',',Qt::SkipEmptyParts)){const QString ref=part.trimmed();if(!refs.contains(ref))refs.append(ref);}value=refs.isEmpty()?QStringLiteral(""):refs.join(',');if(value.size()>256)return false;for(const QString &ref:refs)if(!pattern.match(ref).hasMatch())return false;return true;};
    if (!normalizeReferences(r.potaRef,pota) || !normalizeReferences(r.sotaRef,sota) ||
        !normalizeReferences(r.wwffRef,wwff) || !normalizeReferences(r.iota,iota)) {
        if (error) *error = QStringLiteral("Invalid award reference");
        return false;
    }
    if (r.id.isEmpty()) r.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    if (r.band.isEmpty()) r.band = bandForFrequency(r.frequencyHz);
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    if (r.createdAt <= 0) r.createdAt = now;
    r.updatedAt = now;
    if (!m_database.transaction()) { if (error) *error = m_database.lastError().text(); return false; }
    QSqlQuery q(m_database);
    q.prepare(QStringLiteral(
        "INSERT INTO qso(id,callsign,frequency_hz,frequency_rx_hz,band,band_rx,mode,submode,rst_sent,rst_received,grid,comment,"
        "station_profile_id,station_callsign,operator_callsign,dxcc,country,cq_zone,itu_zone,contest_id,satellite_name,satellite_mode,"
        "propagation_mode,antenna_path,tx_power,antenna,pota_ref,sota_ref,iota,wwff_ref,qsl_manager,qsl_message,qsl_sent,qsl_received,"
        "qsl_sent_date,qsl_received_date,qsl_sent_method,qsl_received_method,lotw_received,eqsl_received,qrz_received,provenance,remote_id,extra_adif_json,created_at,updated_at,deleted) "
        "VALUES(:id,:call,:freq,:rx,:band,:bandrx,:mode,:sub,:rsts,:rstr,:grid,:comment,:profile,:station,:operator,:dxcc,:country,:cq,:itu,"
        ":contest,:sat,:satmode,:prop,:path,:power,:antenna,:pota,:sota,:iota,:wwff,:qslmanager,:qslmessage,:qslsent,:qslreceived,"
        ":qslsdate,:qslrdate,:qslsentmethod,:qslreceivedmethod,:lotw,:eqsl,:qrz,:provenance,:remote,:extra,:created,:updated,:deleted) "
        "ON CONFLICT(id) DO UPDATE SET callsign=excluded.callsign,frequency_hz=excluded.frequency_hz,frequency_rx_hz=excluded.frequency_rx_hz,"
        "band=excluded.band,band_rx=excluded.band_rx,mode=excluded.mode,submode=excluded.submode,rst_sent=excluded.rst_sent,rst_received=excluded.rst_received,"
        "grid=excluded.grid,comment=excluded.comment,station_profile_id=excluded.station_profile_id,station_callsign=excluded.station_callsign,"
        "operator_callsign=excluded.operator_callsign,dxcc=excluded.dxcc,country=excluded.country,cq_zone=excluded.cq_zone,itu_zone=excluded.itu_zone,"
        "contest_id=excluded.contest_id,satellite_name=excluded.satellite_name,satellite_mode=excluded.satellite_mode,propagation_mode=excluded.propagation_mode,"
        "antenna_path=excluded.antenna_path,tx_power=excluded.tx_power,antenna=excluded.antenna,pota_ref=excluded.pota_ref,"
        "sota_ref=excluded.sota_ref,iota=excluded.iota,wwff_ref=excluded.wwff_ref,qsl_manager=excluded.qsl_manager,qsl_message=excluded.qsl_message,"
        "qsl_sent=excluded.qsl_sent,qsl_received=excluded.qsl_received,qsl_sent_date=excluded.qsl_sent_date,qsl_received_date=excluded.qsl_received_date,"
        "qsl_sent_method=excluded.qsl_sent_method,qsl_received_method=excluded.qsl_received_method,lotw_received=excluded.lotw_received,"
        "eqsl_received=excluded.eqsl_received,qrz_received=excluded.qrz_received,provenance=excluded.provenance,remote_id=excluded.remote_id,"
        "extra_adif_json=excluded.extra_adif_json,updated_at=excluded.updated_at,deleted=excluded.deleted"));
    q.bindValue(":id",r.id); q.bindValue(":call",r.callsign); q.bindValue(":freq",r.frequencyHz); q.bindValue(":rx",r.frequencyRxHz);
    q.bindValue(":band",r.band); q.bindValue(":bandrx",r.bandRx); q.bindValue(":mode",r.mode.toUpper()); q.bindValue(":sub",r.submode.toUpper());
    q.bindValue(":rsts",r.rstSent); q.bindValue(":rstr",r.rstReceived); q.bindValue(":grid",r.grid.toUpper()); q.bindValue(":comment",r.comment.left(4096));
    q.bindValue(":profile",r.stationProfileId); q.bindValue(":station",normalizedCallsign(r.stationCallsign)); q.bindValue(":operator",normalizedCallsign(r.operatorCallsign));
    q.bindValue(":dxcc",r.dxcc); q.bindValue(":country",r.country); q.bindValue(":cq",r.cqZone); q.bindValue(":itu",r.ituZone);
    q.bindValue(":contest",r.contestId); q.bindValue(":sat",r.satelliteName); q.bindValue(":satmode",r.satelliteMode);
    q.bindValue(":prop",r.propagationMode); q.bindValue(":path",r.antennaPath); q.bindValue(":power",std::isfinite(r.txPower)?QVariant(r.txPower):QVariant{}); q.bindValue(":antenna",r.antenna);
    q.bindValue(":pota",r.potaRef); q.bindValue(":sota",r.sotaRef); q.bindValue(":iota",r.iota); q.bindValue(":wwff",r.wwffRef);
    q.bindValue(":qslmanager",r.qslManager); q.bindValue(":qslmessage",r.qslMessage); q.bindValue(":qslsent",r.qslSent); q.bindValue(":qslreceived",r.qslReceived);
    q.bindValue(":qslsdate",r.qslSentDate); q.bindValue(":qslrdate",r.qslReceivedDate); q.bindValue(":qslsentmethod",r.qslSentMethod); q.bindValue(":qslreceivedmethod",r.qslReceivedMethod);
    q.bindValue(":lotw",r.lotwReceived); q.bindValue(":eqsl",r.eqslReceived); q.bindValue(":qrz",r.qrzReceived);
    q.bindValue(":provenance",r.provenance); q.bindValue(":remote",r.remoteId);
    q.bindValue(":extra",QString::fromUtf8(QJsonDocument(r.extraAdif).toJson(QJsonDocument::Compact)));
    q.bindValue(":created",r.createdAt); q.bindValue(":updated",r.updatedAt); q.bindValue(":deleted",r.deleted);
    if (!q.exec() || !updateProjection(r, error) || !execute(QStringLiteral("UPDATE desktop_meta SET value=CAST(value AS INTEGER)+1 WHERE key='database_revision'"), error)) {
        if (error && error->isEmpty()) *error = sqlError(q);
        m_database.rollback(); return false;
    }
    if (!m_database.commit()) { if (error) *error = m_database.lastError().text(); return false; }
    emit revisionChanged();
    return true;
}

bool QsoDatabase::updateProjection(const QsoRecord &r, QString *error) {
    QSqlQuery q(m_database);
    q.prepare(QStringLiteral("INSERT OR REPLACE INTO qso_projection(qso_id,callsign_norm,frequency_hz,band,mode,dxcc,grid,cq_zone,itu_zone,wpx_prefix,portable_ref,confirmed,station_profile_id,provenance,created_at,deleted) "
                             "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"));
    const QString portable = !r.potaRef.isEmpty() ? r.potaRef : (!r.sotaRef.isEmpty() ? r.sotaRef : (!r.wwffRef.isEmpty() ? r.wwffRef : r.iota));
    const bool confirmed = r.qslReceived == "Y" || r.lotwReceived == "Y" || r.eqslReceived == "Y" || r.qrzReceived == "Y";
    const auto prefixMatch = QRegularExpression(QStringLiteral("^([A-Z]{1,4})")).match(normalizedCallsign(r.callsign).section('/', -1));
    const QString prefix = prefixMatch.captured(1);
    for (const auto &value : QVariantList{r.id, normalizedCallsign(r.callsign), r.frequencyHz, r.band, r.mode.toUpper(), r.dxcc, r.grid.toUpper(), r.cqZone, r.ituZone, prefix, portable, confirmed, r.stationProfileId, r.provenance, r.createdAt, r.deleted}) q.addBindValue(value);
    if (q.exec()) return true;
    if (error) *error = sqlError(q);
    return false;
}

bool QsoDatabase::tombstone(const QString &id, QString *error) {
    QSqlQuery select(m_database); select.prepare(QStringLiteral("SELECT remote_id,extra_adif_json FROM qso WHERE id=? AND deleted=0")); select.addBindValue(id);
    if (!select.exec() || !select.next()) { if (error) *error = QStringLiteral("QSO not found"); return false; }
    if (!m_database.transaction()) return false;
    QSqlQuery q(m_database); q.prepare(QStringLiteral("UPDATE qso SET deleted=1,updated_at=? WHERE id=?")); q.addBindValue(QDateTime::currentSecsSinceEpoch()); q.addBindValue(id);
    if (!q.exec()) { if (error) *error=sqlError(q); m_database.rollback(); return false; }
    QSqlQuery p(m_database); p.prepare(QStringLiteral("UPDATE qso_projection SET deleted=1 WHERE qso_id=?")); p.addBindValue(id); p.exec();
    QSqlQuery t(m_database); t.prepare(QStringLiteral("INSERT OR REPLACE INTO qso_tombstone(qso_id,remote_id,canonical_hash,deleted_at) VALUES(?,?,?,?)"));
    t.addBindValue(id); t.addBindValue(select.value(0)); t.addBindValue(QString::fromLatin1(QCryptographicHash::hash(select.value(1).toByteArray(),QCryptographicHash::Sha256).toHex())); t.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!t.exec() || !execute(QStringLiteral("UPDATE desktop_meta SET value=CAST(value AS INTEGER)+1 WHERE key='database_revision'"), error)) { if(error&&error->isEmpty())*error=sqlError(t); m_database.rollback(); return false; }
    if (!m_database.commit()) {
        return false;
    }
    emit revisionChanged();
    return true;
}

QVector<QsoRecord> QsoDatabase::page(const QsoQuery &input, QString *error) const {
    QsoQuery query = input; query.limit = qBound(1, query.limit, 250);
    const QSet<QString> columns{"created_at","callsign","frequency_hz","band","mode"};
    if (!columns.contains(query.sortColumn)) query.sortColumn = QStringLiteral("created_at");
    QStringList where{QStringLiteral("deleted=0")}; QVariantList binds;
    if (!query.stationProfileId.isEmpty()) { where << "station_profile_id=?"; binds << query.stationProfileId; }
    if (!query.callsign.isEmpty()) { where << "callsign LIKE ?"; binds << normalizedCallsign(query.callsign)+"%"; }
    if (!query.band.isEmpty()) { where << "band=?"; binds << query.band; }
    if (!query.mode.isEmpty()) { where << "mode=?"; binds << query.mode.toUpper(); }
    if (!query.provenance.isEmpty()) { where << "provenance=?"; binds << query.provenance; }
    if (query.sortColumn == "created_at" && query.cursorCreatedAt != std::numeric_limits<qint64>::max()) {
        where << QStringLiteral("(created_at < ? OR (created_at = ? AND id < ?))");
        binds << query.cursorCreatedAt << query.cursorCreatedAt << query.cursorId;
    }
    QSqlQuery q(m_database);
    q.prepare(QStringLiteral("SELECT * FROM qso WHERE %1 ORDER BY %2 %3,id %3 LIMIT ?")
                  .arg(where.join(" AND "), query.sortColumn, query.sortOrder == Qt::AscendingOrder ? "ASC" : "DESC"));
    for (const auto &value : binds) {
        q.addBindValue(value);
    }
    q.addBindValue(query.limit);
    QVector<QsoRecord> rows;
    if (!q.exec()) { if (error) *error=sqlError(q); return rows; }
    while (q.next()) rows.push_back(fromQuery(q));
    return rows;
}

int QsoDatabase::count(const QsoQuery &query, QString *error) const {
    QStringList where{"deleted=0"}; QVariantList binds;
    if (!query.stationProfileId.isEmpty()) { where << "station_profile_id=?"; binds << query.stationProfileId; }
    if (!query.callsign.isEmpty()) { where << "callsign LIKE ?"; binds << normalizedCallsign(query.callsign)+"%"; }
    if (!query.band.isEmpty()) { where << "band=?"; binds << query.band; }
    if (!query.mode.isEmpty()) { where << "mode=?"; binds << query.mode.toUpper(); }
    if (!query.provenance.isEmpty()) { where << "provenance=?"; binds << query.provenance; }
    QSqlQuery q(m_database); q.prepare(QStringLiteral("SELECT COUNT(*) FROM qso WHERE %1").arg(where.join(" AND ")));
    for(const auto &v:binds)q.addBindValue(v);
    if(!q.exec()||!q.next()){if(error)*error=sqlError(q);return 0;} return q.value(0).toInt();
}

QVariantMap QsoDatabase::workedConfirmed(const QString &callsign, const QString &band, const QString &mode) const {
    QSqlQuery q(m_database); q.prepare(QStringLiteral("SELECT COUNT(*),MAX(confirmed) FROM qso_projection WHERE deleted=0 AND callsign_norm=? AND (?='' OR band=?) AND (?='' OR mode=?)"));
    q.addBindValue(normalizedCallsign(callsign)); q.addBindValue(band); q.addBindValue(band); q.addBindValue(mode.toUpper()); q.addBindValue(mode.toUpper());
    if(!q.exec()||!q.next())return{{"worked",false},{"confirmed",false}};
    return{{"worked",q.value(0).toInt()>0},{"confirmed",q.value(1).toBool()},{"count",q.value(0)}};
}

QVariantMap QsoDatabase::intelligenceSummary(const QsoQuery &) const {
    QSqlQuery q(m_database); q.exec(QStringLiteral("SELECT COUNT(*),COUNT(DISTINCT callsign_norm),COUNT(DISTINCT NULLIF(dxcc,'')),SUM(confirmed),COUNT(DISTINCT NULLIF(grid,'')),COUNT(DISTINCT NULLIF(portable_ref,'')) FROM qso_projection WHERE deleted=0"));
    if (!q.next()) {
        return {};
    }
    return{{"qsos",q.value(0)},{"callsigns",q.value(1)},{"entities",q.value(2)},{"confirmed",q.value(3)},{"grids",q.value(4)},{"portableReferences",q.value(5)},{"awardTruth","Local estimates only; official programme credit is not claimed."}};
}

bool QsoDatabase::rebuildProjection(QString *error) {
    if (!m_database.transaction()) {
        return false;
    }
    if (!execute("DELETE FROM qso_projection",error)) {
        m_database.rollback();
        return false;
    }
    QSqlQuery q(m_database); if(!q.exec("SELECT * FROM qso")){if(error)*error=sqlError(q);m_database.rollback();return false;}
    while(q.next()){if(!updateProjection(fromQuery(q),error)){m_database.rollback();return false;}}
    if (!m_database.commit()) {
        return false;
    }
    return true;
}

bool QsoDatabase::verifyProjection(QString *error) const {
    QSqlQuery q(m_database); if(!q.exec("SELECT (SELECT COUNT(*) FROM qso),(SELECT COUNT(*) FROM qso_projection)" )||!q.next()){if(error)*error=sqlError(q);return false;}
    if(q.value(0).toLongLong()!=q.value(1).toLongLong()){if(error)*error="Projection row count differs from canonical QSO count";return false;} return true;
}

QVariantMap qsoToVariant(const QsoRecord &r) {
    return{{"id",r.id},{"callsign",r.callsign},{"frequencyHz",r.frequencyHz},{"frequencyRxHz",r.frequencyRxHz},{"band",r.band},{"bandRx",r.bandRx},{"mode",r.mode},{"submode",r.submode},{"rstSent",r.rstSent},{"rstReceived",r.rstReceived},{"grid",r.grid},{"comment",r.comment},{"stationProfileId",r.stationProfileId},{"stationCallsign",r.stationCallsign},{"operatorCallsign",r.operatorCallsign},{"dxcc",r.dxcc},{"country",r.country},{"contestId",r.contestId},{"satelliteName",r.satelliteName},{"satelliteMode",r.satelliteMode},{"propagationMode",r.propagationMode},{"antennaPath",r.antennaPath},{"txPower",std::isfinite(r.txPower)?QVariant(r.txPower):QVariant{}},{"antenna",r.antenna},{"potaRef",r.potaRef},{"sotaRef",r.sotaRef},{"iota",r.iota},{"wwffRef",r.wwffRef},{"qslManager",r.qslManager},{"qslMessage",r.qslMessage},{"qslSent",r.qslSent},{"qslReceived",r.qslReceived},{"qslSentDate",r.qslSentDate},{"qslReceivedDate",r.qslReceivedDate},{"qslSentMethod",r.qslSentMethod},{"qslReceivedMethod",r.qslReceivedMethod},{"provenance",r.provenance},{"remoteId",r.remoteId},{"createdAt",r.createdAt},{"deleted",r.deleted},{"extraAdif",r.extraAdif.toVariantMap()}};
}

QsoTableModel::QsoTableModel(QsoDatabase *database,QObject *parent):QAbstractTableModel(parent),m_database(database){reload();}
int QsoTableModel::rowCount(const QModelIndex &p)const{return p.isValid()?0:m_rows.size();}
int QsoTableModel::columnCount(const QModelIndex &p)const{return p.isValid()?0:9;}
QVariant QsoTableModel::data(const QModelIndex &i,int role)const{if(!i.isValid()||i.row()<0||i.row()>=m_rows.size())return{};const auto&r=m_rows.at(i.row());if(role>=Qt::UserRole){const auto map=qsoToVariant(r);return map.value(QString::fromLatin1(roleNames().value(role)));}if(role!=Qt::DisplayRole)return{};switch(i.column()){case 0:return QDateTime::fromSecsSinceEpoch(r.createdAt,QTimeZone::UTC).toString(Qt::ISODate);case 1:return r.callsign;case 2:return r.frequencyHz;case 3:return r.band;case 4:return r.mode;case 5:return r.rstSent;case 6:return r.rstReceived;case 7:return r.grid;case 8:return r.provenance;default:return{};}}
QVariant QsoTableModel::headerData(int s,Qt::Orientation o,int role)const{if(o!=Qt::Horizontal||role!=Qt::DisplayRole)return{};static const QStringList h{"UTC","Callsign","Frequency","Band","Mode","RST S","RST R","Grid","Source"};return h.value(s);}
QHash<int,QByteArray> QsoTableModel::roleNames()const{return{{Qt::UserRole+1,"id"},{Qt::UserRole+2,"callsign"},{Qt::UserRole+3,"frequencyHz"},{Qt::UserRole+4,"band"},{Qt::UserRole+5,"mode"},{Qt::UserRole+6,"rstSent"},{Qt::UserRole+7,"rstReceived"},{Qt::UserRole+8,"grid"},{Qt::UserRole+9,"comment"},{Qt::UserRole+10,"provenance"},{Qt::UserRole+11,"createdAt"}};}
void QsoTableModel::setPageSize(int v){v=qBound(1,v,250);if(v==m_query.limit)return;m_query.limit=v;emit pageSizeChanged();firstPage();}
void QsoTableModel::setFilters(const QString&c,const QString&b,const QString&m,const QString&p){m_query.callsign=c;m_query.band=b;m_query.mode=m;m_query.provenance=p;firstPage();}
void QsoTableModel::reload(){QString e;auto rows=m_database->page(m_query,&e);const int total=m_database->count(m_query,&e);beginResetModel();m_rows=std::move(rows);m_total=total;endResetModel();emit totalChanged();if(!e.isEmpty())emit error(e);}
void QsoTableModel::nextPage(){if(m_rows.isEmpty())return;const auto&last=m_rows.last();m_query.cursorCreatedAt=last.createdAt;m_query.cursorId=last.id;reload();}
void QsoTableModel::firstPage(){m_query.cursorCreatedAt=std::numeric_limits<qint64>::max();m_query.cursorId.clear();reload();}
QVariantMap QsoTableModel::exact(int row)const{return row>=0&&row<m_rows.size()?qsoToVariant(m_rows.at(row)):QVariantMap{};}

} // namespace shackcq::desktop
