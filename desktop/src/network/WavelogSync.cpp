#include "shackcq/desktop/WavelogSync.hpp"

#include <QCryptographicHash>
#include <QDateTime>
#include <QEventLoop>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSqlError>
#include <QSqlQuery>
#include <QTimer>
#include <QTimeZone>
#include <QUrlQuery>
#include <QUuid>

namespace shackcq::desktop {
namespace {

QString safeError(const QSqlQuery &q) { return q.lastError().text().left(500); }

QsoRecord recordById(QsoDatabase *database, const QString &id) {
    QSqlQuery q(database->connection()); q.prepare(QStringLiteral("SELECT * FROM qso WHERE id=?")); q.addBindValue(id);
    if (!q.exec() || !q.next()) return {};
    QsoRecord r; r.id=q.value("id").toString();r.callsign=q.value("callsign").toString();r.frequencyHz=q.value("frequency_hz").toLongLong();
    r.band=q.value("band").toString();r.mode=q.value("mode").toString();r.submode=q.value("submode").toString();r.rstSent=q.value("rst_sent").toString();r.rstReceived=q.value("rst_received").toString();
    r.grid=q.value("grid").toString();r.comment=q.value("comment").toString();r.stationProfileId=q.value("station_profile_id").toString();r.stationCallsign=q.value("station_callsign").toString();r.operatorCallsign=q.value("operator_callsign").toString();
    r.dxcc=q.value("dxcc").toString();r.country=q.value("country").toString();r.cqZone=q.value("cq_zone").toString();r.ituZone=q.value("itu_zone").toString();r.contestId=q.value("contest_id").toString();r.satelliteName=q.value("satellite_name").toString();r.satelliteMode=q.value("satellite_mode").toString();
    r.potaRef=q.value("pota_ref").toString();r.sotaRef=q.value("sota_ref").toString();r.iota=q.value("iota").toString();r.wwffRef=q.value("wwff_ref").toString();r.qslReceived=q.value("qsl_received").toString();r.lotwReceived=q.value("lotw_received").toString();r.eqslReceived=q.value("eqsl_received").toString();r.qrzReceived=q.value("qrz_received").toString();r.createdAt=q.value("created_at").toLongLong();r.remoteId=q.value("remote_id").toString();
    r.extraAdif=QJsonDocument::fromJson(q.value("extra_adif_json").toByteArray()).object();return r;
}

QJsonObject createBody(const WavelogBinding &binding, const CanonicalQso &qso) {
    static const QMap<QString, QString> names{{"CALL","call"},{"BAND","band"},{"BAND_RX","band_rx"},{"MODE","mode"},{"SUBMODE","mode"},{"RST_SENT","rst_sent"},{"RST_RCVD","rst_rcvd"},{"GRIDSQUARE","gridsquare"},{"COMMENT","comment"},{"SAT_NAME","sat_name"},{"SAT_MODE","sat_mode"},{"SOTA_REF","sota_ref"},{"POTA_REF","pota_ref"},{"WWFF_REF","wwff_ref"},{"IOTA","iota"},{"DXCC","dxcc"},{"CQZ","cqz"},{"ITUZ","ituz"},{"CONTEST_ID","contest_id"},{"OPERATOR","operator"},{"STATION_CALLSIGN","station_callsign"}};
    QJsonObject body{{"station_profile_id",binding.remoteStationId.toLongLong()},{"import_type","json"}};
    for(auto it=names.cbegin();it!=names.cend();++it)if(qso.fields.contains(it.key()))body.insert(it.value(),qso.fields.value(it.key()));
    if(const auto value=qso.fields.value("FREQ");!value.isEmpty())body.insert("freq",qRound64(value.toDouble()*1000000.0));
    if(const auto value=qso.fields.value("QSO_DATE");value.size()==8)body.insert("qso_date",QStringLiteral("%1-%2-%3").arg(value.left(4),value.mid(4,2),value.mid(6,2)));
    body.insert("time_on",qso.fields.value("TIME_ON"));return body;
}

} // namespace

QByteArray CanonicalQso::encoded() const { QJsonObject object;for(auto it=fields.cbegin();it!=fields.cend();++it)object.insert(it.key(),it.value());return QJsonDocument(object).toJson(QJsonDocument::Compact); }
QByteArray CanonicalQso::hash() const { return QCryptographicHash::hash(encoded(),QCryptographicHash::Sha256).toHex(); }

QtWavelogEndpoint::QtWavelogEndpoint(QObject *parent):QObject(parent){}

void QtWavelogEndpoint::close() {
    if (m_closed) return;
    m_closed = true;
    const auto replies = m_replies;
    for (auto *reply : replies) if (reply) reply->abort();
}

QUrl QtWavelogEndpoint::normalizedRoot(const QUrl &input) {
    QUrl url=input;if(url.scheme().isEmpty())url=QUrl(QStringLiteral("https://%1").arg(input.toString()));
    if (url.scheme()!=QStringLiteral("https")||url.host().isEmpty()) {
        return {};
    }
    url.setQuery(QString{});
    url.setFragment(QString{});
    QString path=url.path();while(path.endsWith('/'))path.chop(1);
    if(path.endsWith("/index.php"))path+="/api/v2";else if(!path.endsWith("/api/v2")&&!path.endsWith("/index.php/api/v2"))path+="/index.php/api/v2";
    if (!path.endsWith('/')) path += '/';
    url.setPath(path);return url;
}

QVariantMap QtWavelogEndpoint::request(const QUrl &url,const QString &token,const QByteArray &method,const QJsonObject &body) {
    if(m_closed)return{{"ok",false},{"error","Wavelog endpoint is closed"}};
    if(url.scheme()!=QStringLiteral("https"))return{{"ok",false},{"error","HTTPS is required"}};
    QNetworkRequest request(url);request.setRawHeader("Accept","application/json");request.setRawHeader("Authorization",QByteArray("Bearer ")+token.toUtf8());request.setTransferTimeout(30000);
    QNetworkReply *reply=nullptr;if(method=="GET")reply=m_network.get(request);else if(method=="DELETE")reply=m_network.deleteResource(request);else{request.setHeader(QNetworkRequest::ContentTypeHeader,"application/json");reply=m_network.sendCustomRequest(request,method,QJsonDocument(body).toJson(QJsonDocument::Compact));}m_replies.insert(reply);connect(reply,&QObject::destroyed,this,[this,reply]{m_replies.remove(reply);});
    QEventLoop loop;QTimer timer;timer.setSingleShot(true);connect(&timer,&QTimer::timeout,reply,&QNetworkReply::abort);connect(reply,&QNetworkReply::finished,&loop,&QEventLoop::quit);timer.start(30000);loop.exec();
    m_replies.remove(reply);const int status=reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();const QByteArray bytes=reply->read(4194305);const QString networkError=reply->errorString().left(300);reply->deleteLater();if(m_closed)return{{"ok",false},{"status",status},{"error","Wavelog endpoint closed during request"}};
    if(bytes.size()>4194304)return{{"ok",false},{"status",status},{"error","Wavelog response exceeded 4 MiB bound"}};
    if (status==204) {
        return{{"ok",true},{"status",status}};
    }
    QJsonParseError parse;
    const auto document=QJsonDocument::fromJson(bytes,&parse);
    if(parse.error!=QJsonParseError::NoError||!document.isObject())return{{"ok",false},{"status",status},{"error",status?"Malformed Wavelog response":networkError}};
    QVariantMap result=document.object().toVariantMap();result.insert("ok",status>=200&&status<300);result.insert("status",status);if(status<200||status>=300){const auto e=result.value("error").toMap();result.insert("error",e.value("message",networkError).toString().left(300));}return result;
}

QVariantMap QtWavelogEndpoint::capabilities(const QUrl &server,const QString &token){const auto root=normalizedRoot(server);auto result=request(root.resolved(QUrl("token")),token,"GET");const auto data=result.value("data").toMap();result.insert("scopes",data.value("scopes"));result.insert("owner",data.value("owner"));return result;}
QVariantList QtWavelogEndpoint::stations(const QUrl &server,const QString &token){const auto result=request(normalizedRoot(server).resolved(QUrl("station")),token,"GET");return result.value("data").toList();}
QVariantMap QtWavelogEndpoint::page(const WavelogBinding &binding,const QString &token,int pageNumber){QUrl url=normalizedRoot(binding.serverUrl).resolved(QUrl("qso"));QUrlQuery query;query.addQueryItem("station_id",binding.remoteStationId);query.addQueryItem("page",QString::number(qMax(1,pageNumber)));query.addQueryItem("per_page","250");query.addQueryItem("since_id","0");url.setQuery(query);return request(url,token,"GET");}
QVariantMap QtWavelogEndpoint::apply(const WavelogBinding &binding,const QString &token,const QString &operation,const CanonicalQso &qso,const QString &remoteId){QUrl url=normalizedRoot(binding.serverUrl).resolved(QUrl(remoteId.isEmpty()?"qso":QStringLiteral("qso/%1").arg(QString::fromLatin1(QUrl::toPercentEncoding(remoteId)))));if(operation=="DELETE")return request(url,token,"DELETE");return request(url,token,operation=="CREATE"?"POST":"PATCH",createBody(binding,qso));}

WavelogSyncEngine::WavelogSyncEngine(QsoDatabase *database,QObject *parent):QObject(parent),m_database(database){QString error;if(!ensureSchema(&error))m_state=error;}

bool WavelogSyncEngine::ensureSchema(QString *error)const{const QStringList sql={
"CREATE TABLE IF NOT EXISTS wavelog_binding(id TEXT PRIMARY KEY,server_url TEXT NOT NULL,credential_alias TEXT NOT NULL,local_station_profile_id TEXT NOT NULL,remote_station_id TEXT NOT NULL,can_read INTEGER NOT NULL,can_write INTEGER NOT NULL,updated_at INTEGER NOT NULL)",
"CREATE TABLE IF NOT EXISTS wavelog_outbox(id TEXT PRIMARY KEY,binding_id TEXT NOT NULL,qso_id TEXT NOT NULL,operation TEXT NOT NULL,canonical_json TEXT NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at INTEGER,last_error TEXT NOT NULL DEFAULT '',remote_id TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(binding_id,qso_id,operation))",
"CREATE TABLE IF NOT EXISTS wavelog_link(binding_id TEXT NOT NULL,qso_id TEXT NOT NULL,remote_id TEXT NOT NULL,baseline_json TEXT NOT NULL,baseline_hash TEXT NOT NULL,PRIMARY KEY(binding_id,qso_id),UNIQUE(binding_id,remote_id))",
"CREATE TABLE IF NOT EXISTS wavelog_conflict(id TEXT PRIMARY KEY,binding_id TEXT NOT NULL,qso_id TEXT NOT NULL,remote_id TEXT NOT NULL,base_json TEXT NOT NULL,local_json TEXT NOT NULL,remote_json TEXT NOT NULL,fields TEXT NOT NULL,state TEXT NOT NULL,resolution_intent TEXT,created_at INTEGER NOT NULL)",
"CREATE TABLE IF NOT EXISTS wavelog_checkpoint(binding_id TEXT NOT NULL,kind TEXT NOT NULL,page INTEGER NOT NULL,last_remote_id TEXT NOT NULL,completed INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(binding_id,kind))"};
    for(const auto&s:sql){QSqlQuery q(m_database->connection());if(!q.exec(s)){if(error)*error=safeError(q);return false;}}return true;}

int WavelogSyncEngine::pendingCount()const{QSqlQuery q(m_database->connection());q.exec("SELECT COUNT(*) FROM wavelog_outbox WHERE state IN ('PENDING','RETRY_WAIT','BLOCKED')");return q.next()?q.value(0).toInt():0;}
int WavelogSyncEngine::conflictCount()const{QSqlQuery q(m_database->connection());q.exec("SELECT COUNT(*) FROM wavelog_conflict WHERE state='OPEN'");return q.next()?q.value(0).toInt():0;}
void WavelogSyncEngine::setState(QString value){if(m_state==value)return;m_state=std::move(value);emit stateChanged();}

void WavelogSyncEngine::close(){if(m_closed)return;m_closed=true;if(m_endpoint)m_endpoint->close();m_credentialResolver={};setState("Closed");}

bool WavelogSyncEngine::saveBinding(const WavelogBinding&b,QString*error){if(b.id.isEmpty()||!QtWavelogEndpoint::normalizedRoot(b.serverUrl).isValid()||b.credentialAlias.isEmpty()||b.remoteStationId.isEmpty()){if(error)*error="Complete HTTPS URL, credential alias, and station mapping are required";return false;}auto db=m_database->connection();if(!db.transaction())return false;QSqlQuery clear(db);if(!clear.exec("DELETE FROM wavelog_binding")){db.rollback();return false;}QSqlQuery q(db);q.prepare("INSERT INTO wavelog_binding VALUES(?,?,?,?,?,?,?,?)");q.addBindValue(b.id);q.addBindValue(QtWavelogEndpoint::normalizedRoot(b.serverUrl).toString());q.addBindValue(b.credentialAlias);q.addBindValue(b.localStationProfileId);q.addBindValue(b.remoteStationId);q.addBindValue(b.canRead);q.addBindValue(b.canWrite);q.addBindValue(QDateTime::currentSecsSinceEpoch());if(!q.exec()){if(error)*error=safeError(q);db.rollback();return false;}if(!db.commit())return false;setState(b.canWrite?"Ready — read/write":"Ready — read-only");return true;}

bool WavelogSyncEngine::configureBinding(const QString&serverUrl,const QString&credentialAlias,const QString&localStationProfileId,const QString&remoteStationId,bool canWrite){QString error;const WavelogBinding value{QStringLiteral("desktop-primary"),QUrl(serverUrl),credentialAlias,localStationProfileId,remoteStationId,true,canWrite};const bool ok=saveBinding(value,&error);if(!ok)emit this->error(error);return ok;}

std::optional<WavelogBinding> WavelogSyncEngine::binding()const{if(m_closed)return std::nullopt;QSqlQuery q(m_database->connection());if(!q.exec("SELECT * FROM wavelog_binding LIMIT 1")||!q.next())return std::nullopt;return WavelogBinding{q.value("id").toString(),QUrl(q.value("server_url").toString()),q.value("credential_alias").toString(),q.value("local_station_profile_id").toString(),q.value("remote_station_id").toString(),q.value("can_read").toBool(),q.value("can_write").toBool()};}

CanonicalQso WavelogSyncEngine::canonical(const QsoRecord&r){CanonicalQso c;const QDateTime dt=QDateTime::fromSecsSinceEpoch(r.createdAt,QTimeZone::UTC);c.fields={{"QSO_DATE",dt.toString("yyyyMMdd")},{"TIME_ON",dt.toString("HHmmss")},{"CALL",normalizedCallsign(r.callsign)},{"FREQ",QString::number(r.frequencyHz/1000000.0,'f',6)},{"BAND",r.band},{"MODE",r.mode.toUpper()},{"SUBMODE",r.submode.toUpper()},{"RST_SENT",r.rstSent},{"RST_RCVD",r.rstReceived},{"GRIDSQUARE",r.grid.toUpper()},{"COMMENT",r.comment},{"STATION_CALLSIGN",normalizedCallsign(r.stationCallsign)},{"OPERATOR",normalizedCallsign(r.operatorCallsign)},{"DXCC",r.dxcc},{"COUNTRY",r.country},{"CQZ",r.cqZone},{"ITUZ",r.ituZone},{"CONTEST_ID",r.contestId},{"SAT_NAME",r.satelliteName},{"SAT_MODE",r.satelliteMode},{"POTA_REF",r.potaRef},{"SOTA_REF",r.sotaRef},{"IOTA",r.iota},{"WWFF_REF",r.wwffRef},{"QSL_RCVD",r.qslReceived},{"LOTW_QSL_RCVD",r.lotwReceived},{"EQSL_QSL_RCVD",r.eqslReceived}};for(auto it=r.extraAdif.begin();it!=r.extraAdif.end();++it)c.fields.insert(it.key().toUpper(),canonicalAdifValue(it.value().toString()));for(auto it=c.fields.begin();it!=c.fields.end();)if(it.value().isEmpty())it=c.fields.erase(it);else++it;return c;}

MergeResult WavelogSyncEngine::threeWayMerge(const CanonicalQso&base,const CanonicalQso&local,const CanonicalQso&remote){MergeResult r;QSet<QString> keys;for(const auto&k:base.fields.keys())keys.insert(k);for(const auto&k:local.fields.keys())keys.insert(k);for(const auto&k:remote.fields.keys())keys.insert(k);for(const auto&k:keys){const auto b=base.fields.value(k),l=local.fields.value(k),v=remote.fields.value(k);if(l==v)r.merged.fields[k]=l;else if(l==b)r.merged.fields[k]=v;else if(v==b)r.merged.fields[k]=l;else r.conflicts<<k;}if(!r.conflicts.isEmpty())r.disposition="CONFLICT";else if(local.hash()==remote.hash())r.disposition="CONVERGED";else if(base.hash()==remote.hash())r.disposition="PUSH_LOCAL";else if(base.hash()==local.hash())r.disposition="PULL_REMOTE";else r.disposition="SAFE_MERGE";return r;}

bool WavelogSyncEngine::enqueue(const QString&qsoId,const QString&operation,QString*error){const auto b=binding();if(!b||!b->canWrite){if(error)*error="Active binding is not writable";return false;}const auto r=recordById(m_database,qsoId);if(r.id.isEmpty()){if(error)*error="QSO not found";return false;}const auto c=canonical(r);QSqlQuery q(m_database->connection());q.prepare("INSERT INTO wavelog_outbox(id,binding_id,qso_id,operation,canonical_json,state,created_at,updated_at,remote_id) VALUES(?,?,?,?,?,'PENDING',?,?,?) ON CONFLICT(binding_id,qso_id,operation) DO UPDATE SET canonical_json=excluded.canonical_json,state='PENDING',updated_at=excluded.updated_at,last_error='' ");const qint64 now=QDateTime::currentSecsSinceEpoch();q.addBindValue(QUuid::createUuid().toString(QUuid::WithoutBraces));q.addBindValue(b->id);q.addBindValue(qsoId);q.addBindValue(operation);q.addBindValue(c.encoded());q.addBindValue(now);q.addBindValue(now);q.addBindValue(r.remoteId);if(!q.exec()){if(error)*error=safeError(q);return false;}emit queueChanged();return true;}

void WavelogSyncEngine::synchronize(const QString&mode){const auto b=binding();if(!b||!m_endpoint||!m_credentialResolver){emit error("Configure a Wavelog binding and credential vault first");return;}const QString token=m_credentialResolver(b->credentialAlias);if(!token.startsWith("wl2_")){emit error("Credential alias is unavailable or not an API-v2 token");return;}if(!b->canRead){emit error("qso:read scope is required");return;}setState("Synchronizing");retryPending();const QString kind=mode.toUpper();int pageNumber=1,imported=0,conflicts=0;bool more=true;const int maxPages=kind=="QUICK"?1:10000;while(more&&pageNumber<=maxPages){const auto result=m_endpoint->page(*b,token,pageNumber);if(!result.value("ok").toBool()){setState("Error");emit error(result.value("error").toString());return;}const auto rows=result.value("data").toList();for(const auto&value:rows){const auto map=value.toMap();const QString remoteId=map.value("id").toString();if(remoteId.isEmpty())continue;CanonicalQso remote;for(auto it=map.cbegin();it!=map.cend();++it)remote.fields.insert(it.key().toUpper(),canonicalAdifValue(it.value().toString()));QSqlQuery link(m_database->connection());link.prepare("SELECT qso_id,baseline_json FROM wavelog_link WHERE binding_id=? AND remote_id=?");link.addBindValue(b->id);link.addBindValue(remoteId);if(link.exec()&&link.next()){const auto local=canonical(recordById(m_database,link.value(0).toString()));CanonicalQso base;const auto obj=QJsonDocument::fromJson(link.value(1).toByteArray()).object();for(auto it=obj.begin();it!=obj.end();++it)base.fields.insert(it.key(),it.value().toString());const auto merge=threeWayMerge(base,local,remote);if(merge.disposition=="CONFLICT"){QSqlQuery c(m_database->connection());c.prepare("INSERT OR IGNORE INTO wavelog_conflict VALUES(?,?,?,?,?,?,?,?, 'OPEN',NULL,?)");c.addBindValue(QUuid::createUuid().toString(QUuid::WithoutBraces));c.addBindValue(b->id);c.addBindValue(link.value(0));c.addBindValue(remoteId);c.addBindValue(base.encoded());c.addBindValue(local.encoded());c.addBindValue(remote.encoded());c.addBindValue(merge.conflicts.join(','));c.addBindValue(QDateTime::currentSecsSinceEpoch());c.exec();conflicts++;}else if(merge.disposition=="PUSH_LOCAL"||merge.disposition=="SAFE_MERGE")enqueue(link.value(0).toString(),"UPDATE");}else{QsoRecord r;r.id=QStringLiteral("wavelog-%1-%2").arg(b->id,remoteId);r.remoteId=remoteId;r.provenance="remote";r.callsign=remote.fields.value("CALL",map.value("call").toString());const QString frequency=remote.fields.value("FREQ",map.value("freq").toString());r.frequencyHz=frequency.toDouble()>100000?frequency.toLongLong():qRound64(frequency.toDouble()*1000000.0);r.band=remote.fields.value("BAND",map.value("band").toString());r.mode=remote.fields.value("MODE",map.value("mode").toString());r.rstSent=remote.fields.value("RST_SENT","59");r.rstReceived=remote.fields.value("RST_RCVD","59");r.stationProfileId=b->localStationProfileId;r.createdAt=QDateTime::currentSecsSinceEpoch();QString saveError;if(m_database->save(r,&saveError)){QSqlQuery l(m_database->connection());l.prepare("INSERT INTO wavelog_link VALUES(?,?,?,?,?)");l.addBindValue(b->id);l.addBindValue(r.id);l.addBindValue(remoteId);l.addBindValue(remote.encoded());l.addBindValue(remote.hash());l.exec();imported++;}}}const auto meta=result.value("meta").toMap();more=meta.value("has_more",false).toBool();emit progress(pageNumber,imported,0,conflicts);pageNumber++;}setState(conflicts?"Conflicts require review":"Synchronized");emit queueChanged();}

void WavelogSyncEngine::retryPending(){const auto b=binding();if(!b||!b->canWrite||!m_endpoint||!m_credentialResolver)return;const QString token=m_credentialResolver(b->credentialAlias);QSqlQuery q(m_database->connection());q.prepare("SELECT id,qso_id,operation,canonical_json,remote_id,attempts FROM wavelog_outbox WHERE binding_id=? AND state IN ('PENDING','RETRY_WAIT') AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY created_at LIMIT 100");q.addBindValue(b->id);q.addBindValue(QDateTime::currentSecsSinceEpoch());if(!q.exec())return;while(q.next()){CanonicalQso c;const auto obj=QJsonDocument::fromJson(q.value(3).toByteArray()).object();for(auto it=obj.begin();it!=obj.end();++it)c.fields.insert(it.key(),it.value().toString());const auto result=m_endpoint->apply(*b,token,q.value(2).toString(),c,q.value(4).toString());QSqlQuery update(m_database->connection());if(result.value("ok").toBool()){const QString remoteId=result.value("data").toMap().value("id",q.value(4)).toString();update.prepare("UPDATE wavelog_outbox SET state='ACCEPTED',updated_at=?,remote_id=?,last_error='' WHERE id=?");update.addBindValue(QDateTime::currentSecsSinceEpoch());update.addBindValue(remoteId);update.addBindValue(q.value(0));update.exec();if(!remoteId.isEmpty()){QSqlQuery link(m_database->connection());link.prepare("INSERT OR REPLACE INTO wavelog_link VALUES(?,?,?,?,?)");link.addBindValue(b->id);link.addBindValue(q.value(1));link.addBindValue(remoteId);link.addBindValue(c.encoded());link.addBindValue(c.hash());link.exec();}}else{const int status=result.value("status").toInt();const bool ambiguous=(q.value(2)=="CREATE"||q.value(2)=="DELETE")&&(status==0||status>=500);update.prepare("UPDATE wavelog_outbox SET state=?,attempts=attempts+1,next_attempt_at=?,last_error=?,updated_at=? WHERE id=?");update.addBindValue(ambiguous?"BLOCKED":((status==429||status>=500)?"RETRY_WAIT":"BLOCKED"));update.addBindValue(ambiguous?QVariant():QDateTime::currentSecsSinceEpoch()+qMin(3600,60*(1<<qMin(6,q.value(5).toInt()))));update.addBindValue(ambiguous?"Ambiguous write; reconcile before retry":result.value("error").toString().left(300));update.addBindValue(QDateTime::currentSecsSinceEpoch());update.addBindValue(q.value(0));update.exec();}}emit queueChanged();}

bool WavelogSyncEngine::resolveConflict(const QString&id,const QString&resolution,const QVariantMap&merged){if(!QStringList{"Keep Local","Keep Remote","Merge"}.contains(resolution))return false;QSqlQuery q(m_database->connection());q.prepare("SELECT qso_id,local_json,remote_json FROM wavelog_conflict WHERE id=? AND state='OPEN'");q.addBindValue(id);if(!q.exec()||!q.next())return false;if(resolution=="Keep Local")enqueue(q.value(0).toString(),"UPDATE");else if(resolution=="Merge"){CanonicalQso c;for(auto it=merged.begin();it!=merged.end();++it)c.fields.insert(it.key().toUpper(),it.value().toString());QSqlQuery o(m_database->connection());o.prepare("UPDATE wavelog_outbox SET canonical_json=?,state='PENDING' WHERE qso_id=?");o.addBindValue(c.encoded());o.addBindValue(q.value(0));o.exec();}QSqlQuery u(m_database->connection());u.prepare("UPDATE wavelog_conflict SET state='RESOLVED',resolution_intent=? WHERE id=?");u.addBindValue(resolution);u.addBindValue(id);const bool ok=u.exec();emit queueChanged();return ok;}

} // namespace shackcq::desktop
