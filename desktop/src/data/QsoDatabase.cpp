#include "shackcq/desktop/QsoDatabase.hpp"

#include <QDir>
#include <QCryptographicHash>
#include <QFileInfo>
#include <QJsonDocument>
#include <QSqlError>
#include <QSqlQuery>
#include <QUuid>
#include <QRegularExpression>
#include <QSet>
#include <QTimeZone>
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
    r.satelliteMode = q.value("satellite_mode").toString(); r.potaRef = q.value("pota_ref").toString();
    r.sotaRef = q.value("sota_ref").toString(); r.iota = q.value("iota").toString(); r.wwffRef = q.value("wwff_ref").toString();
    r.qslReceived = q.value("qsl_received").toString(); r.lotwReceived = q.value("lotw_received").toString();
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
                       "pota_ref TEXT NOT NULL DEFAULT '',sota_ref TEXT NOT NULL DEFAULT '',iota TEXT NOT NULL DEFAULT '',wwff_ref TEXT NOT NULL DEFAULT '',"
                       "qsl_received TEXT NOT NULL DEFAULT 'N',lotw_received TEXT NOT NULL DEFAULT 'N',eqsl_received TEXT NOT NULL DEFAULT 'N',qrz_received TEXT NOT NULL DEFAULT 'N',"
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
        QStringLiteral("INSERT OR IGNORE INTO desktop_meta(key,value) VALUES('database_revision','0')"),
        QStringLiteral("PRAGMA user_version=16")
    };
    for (const auto &statement : statements) {
        if (!execute(statement, error)) { m_database.rollback(); return false; }
    }
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
        &r.satelliteMode, &r.potaRef, &r.sotaRef, &r.iota, &r.wwffRef,
        &r.qslReceived, &r.lotwReceived, &r.eqslReceived, &r.qrzReceived,
        &r.provenance, &r.remoteId,
    };
    for (QString *field : textFields) {
        if (field->isNull()) *field = QStringLiteral("");
    }
    r.callsign = normalizedCallsign(r.callsign);
    if (r.callsign.isEmpty() || r.frequencyHz <= 0 || r.mode.trimmed().isEmpty()) {
        if (error) *error = QStringLiteral("Callsign, positive frequency, and mode are required");
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
        "pota_ref,sota_ref,iota,wwff_ref,qsl_received,lotw_received,eqsl_received,qrz_received,provenance,remote_id,extra_adif_json,created_at,updated_at,deleted) "
        "VALUES(:id,:call,:freq,:rx,:band,:bandrx,:mode,:sub,:rsts,:rstr,:grid,:comment,:profile,:station,:operator,:dxcc,:country,:cq,:itu,"
        ":contest,:sat,:satmode,:pota,:sota,:iota,:wwff,:qsl,:lotw,:eqsl,:qrz,:provenance,:remote,:extra,:created,:updated,:deleted) "
        "ON CONFLICT(id) DO UPDATE SET callsign=excluded.callsign,frequency_hz=excluded.frequency_hz,frequency_rx_hz=excluded.frequency_rx_hz,"
        "band=excluded.band,band_rx=excluded.band_rx,mode=excluded.mode,submode=excluded.submode,rst_sent=excluded.rst_sent,rst_received=excluded.rst_received,"
        "grid=excluded.grid,comment=excluded.comment,station_profile_id=excluded.station_profile_id,station_callsign=excluded.station_callsign,"
        "operator_callsign=excluded.operator_callsign,dxcc=excluded.dxcc,country=excluded.country,cq_zone=excluded.cq_zone,itu_zone=excluded.itu_zone,"
        "contest_id=excluded.contest_id,satellite_name=excluded.satellite_name,satellite_mode=excluded.satellite_mode,pota_ref=excluded.pota_ref,"
        "sota_ref=excluded.sota_ref,iota=excluded.iota,wwff_ref=excluded.wwff_ref,qsl_received=excluded.qsl_received,lotw_received=excluded.lotw_received,"
        "eqsl_received=excluded.eqsl_received,qrz_received=excluded.qrz_received,provenance=excluded.provenance,remote_id=excluded.remote_id,"
        "extra_adif_json=excluded.extra_adif_json,updated_at=excluded.updated_at,deleted=excluded.deleted"));
    q.bindValue(":id",r.id); q.bindValue(":call",r.callsign); q.bindValue(":freq",r.frequencyHz); q.bindValue(":rx",r.frequencyRxHz);
    q.bindValue(":band",r.band); q.bindValue(":bandrx",r.bandRx); q.bindValue(":mode",r.mode.toUpper()); q.bindValue(":sub",r.submode.toUpper());
    q.bindValue(":rsts",r.rstSent); q.bindValue(":rstr",r.rstReceived); q.bindValue(":grid",r.grid.toUpper()); q.bindValue(":comment",r.comment.left(4096));
    q.bindValue(":profile",r.stationProfileId); q.bindValue(":station",normalizedCallsign(r.stationCallsign)); q.bindValue(":operator",normalizedCallsign(r.operatorCallsign));
    q.bindValue(":dxcc",r.dxcc); q.bindValue(":country",r.country); q.bindValue(":cq",r.cqZone); q.bindValue(":itu",r.ituZone);
    q.bindValue(":contest",r.contestId); q.bindValue(":sat",r.satelliteName); q.bindValue(":satmode",r.satelliteMode);
    q.bindValue(":pota",r.potaRef); q.bindValue(":sota",r.sotaRef); q.bindValue(":iota",r.iota); q.bindValue(":wwff",r.wwffRef);
    q.bindValue(":qsl",r.qslReceived); q.bindValue(":lotw",r.lotwReceived); q.bindValue(":eqsl",r.eqslReceived); q.bindValue(":qrz",r.qrzReceived);
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
    return{{"id",r.id},{"callsign",r.callsign},{"frequencyHz",r.frequencyHz},{"frequencyRxHz",r.frequencyRxHz},{"band",r.band},{"bandRx",r.bandRx},{"mode",r.mode},{"submode",r.submode},{"rstSent",r.rstSent},{"rstReceived",r.rstReceived},{"grid",r.grid},{"comment",r.comment},{"stationProfileId",r.stationProfileId},{"stationCallsign",r.stationCallsign},{"operatorCallsign",r.operatorCallsign},{"dxcc",r.dxcc},{"country",r.country},{"contestId",r.contestId},{"satelliteName",r.satelliteName},{"satelliteMode",r.satelliteMode},{"potaRef",r.potaRef},{"sotaRef",r.sotaRef},{"iota",r.iota},{"wwffRef",r.wwffRef},{"provenance",r.provenance},{"remoteId",r.remoteId},{"createdAt",r.createdAt},{"deleted",r.deleted},{"extraAdif",r.extraAdif.toVariantMap()}};
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
