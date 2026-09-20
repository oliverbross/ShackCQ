#include "shackcq/desktop/WavelogSync.hpp"

#include <QCryptographicHash>
#include <QDateTime>
#include <QEventLoop>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QSqlError>
#include <QSqlQuery>
#include <QTimer>
#include <QTimeZone>
#include <QUrlQuery>
#include <QUuid>
#include <cmath>
#include <limits>

namespace shackcq::desktop {
namespace {

QString safeError(const QSqlQuery &q) { return q.lastError().text().left(500); }
const QSet<QString> &mappedWavelogFields();
bool safeExtraAdifName(const QString &name);

QUrl normalizedBase(const QUrl &input) {
    QUrl url=input;if(url.scheme().isEmpty())url=QUrl(QStringLiteral("https://%1").arg(input.toString()));
    if(url.scheme()!=QStringLiteral("https")||url.host().isEmpty())return{};
    url.setQuery(QString{});url.setFragment(QString{});QString path=url.path();while(path.endsWith('/'))path.chop(1);
    if(path.endsWith("/api/v2"))path.chop(7);while(path.endsWith('/'))path.chop(1);
    if(!path.endsWith("/index.php"))path+=QStringLiteral("/index.php");path+=QChar('/');url.setPath(path);return url;
}

QVariantList legacyAdifRows(const QByteArray &adif, QString *error) {
    QVariantList rows;qsizetype cursor=0;
    while(cursor<adif.size()){
        const qsizetype marker=adif.toUpper().indexOf("<EOR>",cursor);if(marker<0)break;
        const QByteArray record=adif.mid(cursor,marker+5-cursor);cursor=marker+5;QVariantMap row;qsizetype pos=0;
        while(pos<record.size()){
            const qsizetype open=record.indexOf('<',pos),close=open<0?-1:record.indexOf('>',open+1);if(open<0)break;if(close<0){if(error)*error="Unterminated legacy ADIF field";return{};}
            const auto descriptor=record.mid(open+1,close-open-1).split(':');const QString name=QString::fromLatin1(descriptor.value(0)).trimmed().toUpper();if(name=="EOR"||name=="EOH"){pos=close+1;continue;}
            bool ok=false;const int length=descriptor.value(1).toInt(&ok);if(!ok||length<0||length>1048576||close+1+length>record.size()){if(error)*error="Invalid legacy ADIF field length";return{};}
            if(mappedWavelogFields().contains(name)||safeExtraAdifName(name)||name=="QSO_ID")row.insert(name,QString::fromUtf8(record.mid(close+1,length)));pos=close+1+length;
        }
        const QString id=row.value("QSO_ID",row.value("ID")).toString();if(!id.isEmpty())row.insert("id",id);if(!row.isEmpty())rows.push_back(row);
    }
    return rows;
}

QByteArray legacyAdif(const CanonicalQso &qso){QByteArray out;for(auto it=qso.fields.cbegin();it!=qso.fields.cend();++it){const QByteArray value=it.value().toUtf8();if(!value.isEmpty())out+='<'+it.key().toLatin1()+':'+QByteArray::number(value.size())+'>'+value;}return out+"<EOR>";}

const QSet<QString> &mappedWavelogFields() {
    static const QSet<QString> fields{"ID","CALL","FREQ","BAND","MODE","SUBMODE","RST_SENT","RST_RCVD","GRIDSQUARE","COMMENT","STATION_CALLSIGN","OPERATOR","DXCC","COUNTRY","CQZ","ITUZ","CONTEST_ID","QSO_DATE","TIME_ON","STATION_PROFILE_ID","IMPORT_TYPE","SAT_NAME","SAT_MODE","PROP_MODE","ANT_PATH","TX_PWR","MY_ANTENNA","POTA_REF","SOTA_REF","WWFF_REF","IOTA","QSL_VIA","QSLMSG","QSL_SENT","QSL_RCVD","QSLSDATE","QSLRDATE","QSL_SENT_VIA","QSL_RCVD_VIA"};
    return fields;
}

bool safeExtraAdifName(const QString &name) {
    const QString upper = name.toUpper();
    static const QRegularExpression valid(QStringLiteral("^[A-Z][A-Z0-9_]{0,63}$"));
    static const QRegularExpression sensitive(QStringLiteral("(?:^|_)(?:TOKEN|SECRET|PASSWORD|API_KEY|AUTH)(?:_|$)"));
    static const QSet<QString> denied{"ID","QSO_ID","APP_SHACKCQ_ID","APP_SHACKCQ_OPERATION_ID"};
    return valid.match(upper).hasMatch() && !denied.contains(upper) && !sensitive.match(upper).hasMatch();
}

const QSet<QString> &patchRetainedFields() {
    static const QSet<QString> fields{"ANT_PATH","MY_ANTENNA","QSLMSG","QSL_SENT","QSL_RCVD","QSLSDATE","QSLRDATE","QSL_SENT_VIA","QSL_RCVD_VIA"};
    return fields;
}

CanonicalQso deliveredCanonical(const CanonicalQso &source, const QString &operation) {
    CanonicalQso delivered = source;
    if (operation == QStringLiteral("UPDATE")) for (const QString &field : patchRetainedFields()) delivered.fields.remove(field);
    return delivered;
}

QStringList retainedFields(const CanonicalQso &source, const QString &operation) {
    QStringList retained;
    if (operation == QStringLiteral("UPDATE")) for (const QString &field : patchRetainedFields()) if (source.fields.contains(field)) retained.append(field);
    retained.sort(); return retained;
}

QString isoAdifDate(const QString &value) {
    const QString compact = value.trimmed();
    if (compact.size() == 8) return QStringLiteral("%1-%2-%3").arg(compact.left(4), compact.mid(4, 2), compact.mid(6, 2));
    return compact;
}

QsoRecord recordById(QsoDatabase *database, const QString &id) {
    QSqlQuery q(database->connection()); q.prepare(QStringLiteral("SELECT * FROM qso WHERE id=?")); q.addBindValue(id);
    if (!q.exec() || !q.next()) return {};
    QsoRecord r; r.id=q.value("id").toString();r.callsign=q.value("callsign").toString();r.frequencyHz=q.value("frequency_hz").toLongLong();
    r.band=q.value("band").toString();r.mode=q.value("mode").toString();r.submode=q.value("submode").toString();r.rstSent=q.value("rst_sent").toString();r.rstReceived=q.value("rst_received").toString();
    r.grid=q.value("grid").toString();r.comment=q.value("comment").toString();r.stationProfileId=q.value("station_profile_id").toString();r.stationCallsign=q.value("station_callsign").toString();r.operatorCallsign=q.value("operator_callsign").toString();
    r.dxcc=q.value("dxcc").toString();r.country=q.value("country").toString();r.cqZone=q.value("cq_zone").toString();r.ituZone=q.value("itu_zone").toString();r.contestId=q.value("contest_id").toString();r.satelliteName=q.value("satellite_name").toString();r.satelliteMode=q.value("satellite_mode").toString();
    r.propagationMode=q.value("propagation_mode").toString();r.antennaPath=q.value("antenna_path").toString();r.txPower=q.isNull("tx_power")?std::numeric_limits<double>::quiet_NaN():q.value("tx_power").toDouble();r.antenna=q.value("antenna").toString();
    r.potaRef=q.value("pota_ref").toString();r.sotaRef=q.value("sota_ref").toString();r.iota=q.value("iota").toString();r.wwffRef=q.value("wwff_ref").toString();
    r.qslManager=q.value("qsl_manager").toString();r.qslMessage=q.value("qsl_message").toString();r.qslSent=q.value("qsl_sent").toString();r.qslReceived=q.value("qsl_received").toString();r.qslSentDate=q.value("qsl_sent_date").toString();r.qslReceivedDate=q.value("qsl_received_date").toString();r.qslSentMethod=q.value("qsl_sent_method").toString();r.qslReceivedMethod=q.value("qsl_received_method").toString();
    r.lotwReceived=q.value("lotw_received").toString();r.eqslReceived=q.value("eqsl_received").toString();r.qrzReceived=q.value("qrz_received").toString();r.createdAt=q.value("created_at").toLongLong();r.remoteId=q.value("remote_id").toString();
    r.extraAdif=QJsonDocument::fromJson(q.value("extra_adif_json").toByteArray()).object();return r;
}

QJsonObject createBody(const WavelogBinding &binding, const CanonicalQso &qso, bool create) {
    QMap<QString, QString> names{{"CALL","call"},{"BAND","band"},{"BAND_RX","band_rx"},{"MODE","mode"},{"SUBMODE","mode"},{"RST_SENT","rst_sent"},{"RST_RCVD","rst_rcvd"},{"GRIDSQUARE","gridsquare"},{"COMMENT","comment"},{"SAT_NAME","sat_name"},{"SAT_MODE","sat_mode"},{"PROP_MODE","prop_mode"},{"TX_PWR","tx_pwr"},{"QSL_VIA","qsl_via"},{"SOTA_REF","sota_ref"},{"POTA_REF","pota_ref"},{"WWFF_REF","wwff_ref"},{"IOTA","iota"},{"DXCC","dxcc"},{"CQZ","cqz"},{"ITUZ","ituz"},{"CONTEST_ID","contest_id"},{"OPERATOR","operator"},{"STATION_CALLSIGN","station_callsign"}};
    if(create)names.insert({{"ANT_PATH","ant_path"},{"MY_ANTENNA","my_antenna"},{"QSLMSG","qslmsg"},{"QSL_SENT","qsl_sent"},{"QSL_RCVD","qsl_rcvd"},{"QSLSDATE","qslsdate"},{"QSLRDATE","qslrdate"},{"QSL_SENT_VIA","qsl_sent_via"},{"QSL_RCVD_VIA","qsl_rcvd_via"}});
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
    QUrl url=normalizedBase(input);if(!url.isValid())return{};QString path=url.path();path+=QStringLiteral("api/v2/");url.setPath(path);return url;
}

QVariantMap QtWavelogEndpoint::request(const QUrl &url,const QString &token,const QByteArray &method,const QJsonObject &body) {
    if(m_closed)return{{"ok",false},{"error","Wavelog endpoint is closed"}};
    if(url.scheme()!=QStringLiteral("https"))return{{"ok",false},{"error","HTTPS is required"}};
    QNetworkRequest request(url);request.setRawHeader("Accept","application/json");if(token.startsWith("wl2_"))request.setRawHeader("Authorization",QByteArray("Bearer ")+token.toUtf8());request.setTransferTimeout(30000);
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

QVariantMap QtWavelogEndpoint::capabilities(const QUrl &server,const QString &token){if(!token.startsWith("wl2_")){auto result=request(normalizedBase(server).resolved(QUrl(QStringLiteral("api/version"))),{},"POST",{{"key",token}});result.insert("apiMode","LEGACY");result.insert("scopes",QStringList{"station:read","qso:read","qso:create"});result.insert("limitations",QStringList{"qso:update unavailable","qso:delete unavailable","no standard rotor, audio, digital-mode or PTT capability"});return result;}const auto root=normalizedRoot(server);auto result=request(root.resolved(QUrl("token")),token,"GET");const auto data=result.value("data").toMap();result.insert("apiMode","V2");result.insert("scopes",data.value("scopes"));result.insert("owner",data.value("owner"));return result;}
QVariantList QtWavelogEndpoint::stations(const QUrl &server,const QString &token){if(!token.startsWith("wl2_")){const auto result=request(normalizedBase(server).resolved(QUrl(QStringLiteral("api/station_info/%1").arg(QString::fromLatin1(QUrl::toPercentEncoding(token))))),{},"GET");const auto data=result.value("data");return data.canConvert<QVariantList>()?data.toList():QVariantList{data};}const auto result=request(normalizedRoot(server).resolved(QUrl("station")),token,"GET");return result.value("data").toList();}
QVariantMap QtWavelogEndpoint::page(const WavelogBinding &binding,const QString &token,int pageNumber){const bool legacy=binding.apiMode=="LEGACY"||(binding.apiMode=="AUTO"&&!token.startsWith("wl2_"));if(legacy){const QString key=binding.id;const qint64 fromId=pageNumber<=1?0:m_legacyCursors.value(key,0);auto result=request(normalizedBase(binding.serverUrl).resolved(QUrl(QStringLiteral("api/get_contacts_adif"))),{},"POST",{{"key",token},{"station_id",binding.remoteStationId},{"fetchfromid",fromId},{"limit",250}});if(!result.value("ok").toBool())return result;const QVariant value=result.value("data");const QVariantMap data=value.toMap();const QByteArray adif=(value.typeId()==QMetaType::QString?value.toString():data.value("adif",data.value("data",data.value("result"))).toString()).toUtf8();QString error;const QVariantList rows=legacyAdifRows(adif,&error);if(!error.isEmpty())return{{"ok",false},{"error",error}};qint64 last=data.value("lastfetchedid",result.value("lastfetchedid",fromId)).toLongLong();if(last<=fromId)for(const auto &row:rows)last=qMax(last,row.toMap().value("id").toLongLong());m_legacyCursors.insert(key,last);result.insert("data",rows);result.insert("meta",QVariantMap{{"has_more",rows.size()>=250&&last>fromId}});return result;}QUrl url=normalizedRoot(binding.serverUrl).resolved(QUrl("qso"));QUrlQuery query;query.addQueryItem("station_id",binding.remoteStationId);query.addQueryItem("page",QString::number(qMax(1,pageNumber)));query.addQueryItem("per_page","250");query.addQueryItem("since_id","0");url.setQuery(query);return request(url,token,"GET");}
QJsonObject QtWavelogEndpoint::requestBody(const WavelogBinding &binding,const CanonicalQso &qso,const QString &operation){return createBody(binding,qso,operation==QStringLiteral("CREATE"));}
QVariantMap QtWavelogEndpoint::apply(const WavelogBinding &binding,const QString &token,const QString &operation,const CanonicalQso &qso,const QString &remoteId){const bool legacy=binding.apiMode=="LEGACY"||(binding.apiMode=="AUTO"&&!token.startsWith("wl2_"));if(legacy){if(operation!="CREATE")return{{"ok",false},{"status",405},{"error",operation=="DELETE"?"Legacy Wavelog API does not support delete":"Legacy Wavelog API does not support update"}};return request(normalizedBase(binding.serverUrl).resolved(QUrl(QStringLiteral("api/qso"))),{},"POST",{{"key",token},{"station_profile_id",binding.remoteStationId.toLongLong()},{"type","adif"},{"string",QString::fromUtf8(legacyAdif(qso))}});}QUrl url=normalizedRoot(binding.serverUrl).resolved(QUrl(remoteId.isEmpty()?"qso":QStringLiteral("qso/%1").arg(QString::fromLatin1(QUrl::toPercentEncoding(remoteId)))));if(operation=="DELETE")return request(url,token,"DELETE");return request(url,token,operation=="CREATE"?"POST":"PATCH",requestBody(binding,qso,operation));}

WavelogSyncEngine::WavelogSyncEngine(QsoDatabase *database,QObject *parent):QObject(parent),m_database(database){QString error;if(!ensureSchema(&error))m_state=error;}

bool WavelogSyncEngine::ensureSchema(QString *error)const{const QStringList sql={
"CREATE TABLE IF NOT EXISTS wavelog_binding(id TEXT PRIMARY KEY,server_url TEXT NOT NULL,credential_alias TEXT NOT NULL,local_station_profile_id TEXT NOT NULL,remote_station_id TEXT NOT NULL,can_read INTEGER NOT NULL,can_write INTEGER NOT NULL,updated_at INTEGER NOT NULL,api_mode TEXT NOT NULL DEFAULT 'AUTO')",
"CREATE TABLE IF NOT EXISTS wavelog_outbox(id TEXT PRIMARY KEY,binding_id TEXT NOT NULL,qso_id TEXT NOT NULL,operation TEXT NOT NULL,canonical_json TEXT NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at INTEGER,last_error TEXT NOT NULL DEFAULT '',retained_fields TEXT NOT NULL DEFAULT '',remote_id TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(binding_id,qso_id,operation))",
"CREATE TABLE IF NOT EXISTS wavelog_link(binding_id TEXT NOT NULL,qso_id TEXT NOT NULL,remote_id TEXT NOT NULL,baseline_json TEXT NOT NULL,baseline_hash TEXT NOT NULL,retained_fields TEXT NOT NULL DEFAULT '',PRIMARY KEY(binding_id,qso_id),UNIQUE(binding_id,remote_id))",
"CREATE TABLE IF NOT EXISTS wavelog_conflict(id TEXT PRIMARY KEY,binding_id TEXT NOT NULL,qso_id TEXT NOT NULL,remote_id TEXT NOT NULL,base_json TEXT NOT NULL,local_json TEXT NOT NULL,remote_json TEXT NOT NULL,fields TEXT NOT NULL,state TEXT NOT NULL,resolution_intent TEXT,created_at INTEGER NOT NULL)",
"CREATE TABLE IF NOT EXISTS wavelog_checkpoint(binding_id TEXT NOT NULL,kind TEXT NOT NULL,page INTEGER NOT NULL,last_remote_id TEXT NOT NULL,completed INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(binding_id,kind))"};
    for(const auto&s:sql){QSqlQuery q(m_database->connection());if(!q.exec(s)){if(error)*error=safeError(q);return false;}}
    const auto ensureColumn=[&](const QString &table,const QString &column,const QString &definition){QSqlQuery info(m_database->connection());if(!info.exec(QStringLiteral("PRAGMA table_info(%1)").arg(table))){if(error)*error=safeError(info);return false;}bool found=false;while(info.next())found=found||info.value(1).toString()==column;if(found)return true;QSqlQuery alter(m_database->connection());if(alter.exec(QStringLiteral("ALTER TABLE %1 ADD COLUMN %2 %3").arg(table,column,definition)))return true;if(error)*error=safeError(alter);return false;};
    return ensureColumn("wavelog_outbox","retained_fields","TEXT NOT NULL DEFAULT ''")&&ensureColumn("wavelog_link","retained_fields","TEXT NOT NULL DEFAULT ''")&&ensureColumn("wavelog_binding","api_mode","TEXT NOT NULL DEFAULT 'AUTO'");}

int WavelogSyncEngine::pendingCount()const{QSqlQuery q(m_database->connection());q.exec("SELECT COUNT(*) FROM wavelog_outbox WHERE state IN ('PENDING','RETRY_WAIT','BLOCKED')");return q.next()?q.value(0).toInt():0;}
int WavelogSyncEngine::conflictCount()const{QSqlQuery q(m_database->connection());q.exec("SELECT COUNT(*) FROM wavelog_conflict WHERE state='OPEN'");return q.next()?q.value(0).toInt():0;}
void WavelogSyncEngine::setState(QString value){if(m_state==value)return;m_state=std::move(value);emit stateChanged();}

void WavelogSyncEngine::close(){if(m_closed)return;m_closed=true;if(m_endpoint)m_endpoint->close();m_credentialResolver={};setState("Closed");}

bool WavelogSyncEngine::saveBinding(const WavelogBinding&b,QString*error){const QString mode=b.apiMode.trimmed().toUpper();if(b.id.isEmpty()||!normalizedBase(b.serverUrl).isValid()||b.credentialAlias.isEmpty()||b.remoteStationId.isEmpty()||!QStringList{"AUTO","V1","LEGACY","V2"}.contains(mode)){if(error)*error="Complete HTTPS URL, API mode, credential alias, and station mapping are required";return false;}auto db=m_database->connection();if(!db.transaction())return false;QSqlQuery clear(db);if(!clear.exec("DELETE FROM wavelog_binding")){db.rollback();return false;}QSqlQuery q(db);q.prepare("INSERT INTO wavelog_binding(id,server_url,credential_alias,local_station_profile_id,remote_station_id,can_read,can_write,updated_at,api_mode) VALUES(?,?,?,?,?,?,?,?,?)");q.addBindValue(b.id);q.addBindValue(normalizedBase(b.serverUrl).toString());q.addBindValue(b.credentialAlias);q.addBindValue(b.localStationProfileId);q.addBindValue(b.remoteStationId);q.addBindValue(b.canRead);q.addBindValue(b.canWrite);q.addBindValue(QDateTime::currentSecsSinceEpoch());q.addBindValue(mode=="V1"?"LEGACY":mode);if(!q.exec()){if(error)*error=safeError(q);db.rollback();return false;}if(!db.commit())return false;setState(b.canWrite?"Ready — read/write":"Ready — read-only");return true;}

bool WavelogSyncEngine::configureBinding(const QString&serverUrl,const QString&credentialAlias,const QString&localStationProfileId,const QString&remoteStationId,bool canWrite,const QString&apiMode){QString error;const WavelogBinding value{QStringLiteral("desktop-primary"),QUrl(serverUrl),credentialAlias,localStationProfileId,remoteStationId,true,canWrite,apiMode};const bool ok=saveBinding(value,&error);if(!ok)emit this->error(error);return ok;}

std::optional<WavelogBinding> WavelogSyncEngine::binding()const{if(m_closed)return std::nullopt;QSqlQuery q(m_database->connection());if(!q.exec("SELECT * FROM wavelog_binding LIMIT 1")||!q.next())return std::nullopt;return WavelogBinding{q.value("id").toString(),QUrl(q.value("server_url").toString()),q.value("credential_alias").toString(),q.value("local_station_profile_id").toString(),q.value("remote_station_id").toString(),q.value("can_read").toBool(),q.value("can_write").toBool(),q.value("api_mode").toString()};}

CanonicalQso WavelogSyncEngine::canonical(const QsoRecord&r){CanonicalQso c;const QDateTime dt=QDateTime::fromSecsSinceEpoch(r.createdAt,QTimeZone::UTC);c.fields={{"QSO_DATE",dt.toString("yyyyMMdd")},{"TIME_ON",dt.toString("HHmmss")},{"CALL",normalizedCallsign(r.callsign)},{"FREQ",QString::number(r.frequencyHz/1000000.0,'f',6)},{"BAND",r.band},{"MODE",r.mode.toUpper()},{"SUBMODE",r.submode.toUpper()},{"RST_SENT",r.rstSent},{"RST_RCVD",r.rstReceived},{"GRIDSQUARE",r.grid.toUpper()},{"COMMENT",r.comment},{"STATION_CALLSIGN",normalizedCallsign(r.stationCallsign)},{"OPERATOR",normalizedCallsign(r.operatorCallsign)},{"DXCC",r.dxcc},{"COUNTRY",r.country},{"CQZ",r.cqZone},{"ITUZ",r.ituZone},{"CONTEST_ID",r.contestId},{"SAT_NAME",r.satelliteName},{"SAT_MODE",r.satelliteMode},{"PROP_MODE",r.propagationMode},{"ANT_PATH",r.antennaPath},{"TX_PWR",std::isfinite(r.txPower)?QString::number(r.txPower,'g',12):QString{}},{"MY_ANTENNA",r.antenna},{"POTA_REF",r.potaRef},{"SOTA_REF",r.sotaRef},{"IOTA",r.iota},{"WWFF_REF",r.wwffRef},{"QSL_VIA",r.qslManager},{"QSLMSG",r.qslMessage},{"QSL_SENT",r.qslSent},{"QSL_RCVD",r.qslReceived},{"QSLSDATE",QString(r.qslSentDate).remove('-')},{"QSLRDATE",QString(r.qslReceivedDate).remove('-')},{"QSL_SENT_VIA",r.qslSentMethod},{"QSL_RCVD_VIA",r.qslReceivedMethod},{"LOTW_QSL_RCVD",r.lotwReceived},{"EQSL_QSL_RCVD",r.eqslReceived}};for(auto it=r.extraAdif.begin();it!=r.extraAdif.end();++it){const QString key=it.key().toUpper(),value=it.value().toString();if(!c.fields.contains(key)&&safeExtraAdifName(key)&&value.toUtf8().size()<=512)c.fields.insert(key,canonicalAdifValue(value));}for(auto it=c.fields.begin();it!=c.fields.end();)if(it.value().isEmpty())it=c.fields.erase(it);else++it;return c;}

MergeResult WavelogSyncEngine::threeWayMerge(const CanonicalQso&base,const CanonicalQso&local,const CanonicalQso&remote){MergeResult r;QSet<QString> keys;for(const auto&k:base.fields.keys())keys.insert(k);for(const auto&k:local.fields.keys())keys.insert(k);for(const auto&k:remote.fields.keys())keys.insert(k);for(const auto&k:keys){const auto b=base.fields.value(k),l=local.fields.value(k),v=remote.fields.value(k);if(l==v)r.merged.fields[k]=l;else if(l==b)r.merged.fields[k]=v;else if(v==b)r.merged.fields[k]=l;else r.conflicts<<k;}if(!r.conflicts.isEmpty())r.disposition="CONFLICT";else if(local.hash()==remote.hash())r.disposition="CONVERGED";else if(base.hash()==remote.hash())r.disposition="PUSH_LOCAL";else if(base.hash()==local.hash())r.disposition="PULL_REMOTE";else r.disposition="SAFE_MERGE";return r;}

bool WavelogSyncEngine::enqueue(const QString&qsoId,const QString&operation,QString*error){const auto b=binding();if(!b||!b->canWrite){if(error)*error="Active binding is not writable";return false;}const auto r=recordById(m_database,qsoId);if(r.id.isEmpty()){if(error)*error="QSO not found";return false;}const auto c=canonical(r);QSqlQuery q(m_database->connection());q.prepare("INSERT INTO wavelog_outbox(id,binding_id,qso_id,operation,canonical_json,state,created_at,updated_at,remote_id) VALUES(?,?,?,?,?,'PENDING',?,?,?) ON CONFLICT(binding_id,qso_id,operation) DO UPDATE SET canonical_json=excluded.canonical_json,state='PENDING',updated_at=excluded.updated_at,last_error='',retained_fields='' ");const qint64 now=QDateTime::currentSecsSinceEpoch();q.addBindValue(QUuid::createUuid().toString(QUuid::WithoutBraces));q.addBindValue(b->id);q.addBindValue(qsoId);q.addBindValue(operation);q.addBindValue(c.encoded());q.addBindValue(now);q.addBindValue(now);q.addBindValue(r.remoteId);if(!q.exec()){if(error)*error=safeError(q);return false;}emit queueChanged();return true;}

void WavelogSyncEngine::synchronize(const QString &mode) {
    const auto b = binding();
    if (!b || !m_endpoint || !m_credentialResolver) { emit error("Configure a Wavelog binding and credential vault first"); return; }
    const QString token = m_credentialResolver(b->credentialAlias);
    if (token.trimmed().isEmpty()) { emit error("Credential alias is unavailable"); return; }
    if (!b->canRead) { emit error("qso:read scope is required"); return; }
    setState("Synchronizing"); retryPending();
    const QString kind = mode.toUpper(); int pageNumber = 1, imported = 0, conflicts = 0; bool more = true;
    const int maxPages = kind == "QUICK" ? 1 : 10000;
    while (more && pageNumber <= maxPages) {
        const auto result = m_endpoint->page(*b, token, pageNumber);
        if (!result.value("ok").toBool()) { setState("Error"); emit error(result.value("error").toString()); return; }
        const auto rows = result.value("data").toList();
        for (const auto &value : rows) {
            const auto map = value.toMap(); const QString remoteId = map.value("id").toString(); if (remoteId.isEmpty()) continue;
            CanonicalQso remote; for (auto it = map.cbegin(); it != map.cend(); ++it) { const QString key=it.key().toUpper(),raw=it.value().toString(); if(mappedWavelogFields().contains(key)||(safeExtraAdifName(key)&&raw.toUtf8().size()<=512)) remote.fields.insert(key,canonicalAdifValue(raw)); }
            QSqlQuery link(m_database->connection()); link.prepare("SELECT qso_id,baseline_json,retained_fields FROM wavelog_link WHERE binding_id=? AND remote_id=?"); link.addBindValue(b->id); link.addBindValue(remoteId);
            if (link.exec() && link.next()) {
                auto local = canonical(recordById(m_database, link.value(0).toString())); CanonicalQso base; auto comparableRemote = remote;
                const auto obj = QJsonDocument::fromJson(link.value(1).toByteArray()).object(); for (auto it = obj.begin(); it != obj.end(); ++it) base.fields.insert(it.key(), it.value().toString());
                for (const QString &field : link.value(2).toString().split(',',Qt::SkipEmptyParts)) { local.fields.remove(field); comparableRemote.fields.remove(field); }
                const auto merge = threeWayMerge(base, local, comparableRemote);
                if (merge.disposition == "CONFLICT") {
                    QSqlQuery c(m_database->connection()); c.prepare("INSERT OR IGNORE INTO wavelog_conflict VALUES(?,?,?,?,?,?,?,?, 'OPEN',NULL,?)");
                    c.addBindValue(QUuid::createUuid().toString(QUuid::WithoutBraces)); c.addBindValue(b->id); c.addBindValue(link.value(0)); c.addBindValue(remoteId); c.addBindValue(base.encoded()); c.addBindValue(local.encoded()); c.addBindValue(remote.encoded()); c.addBindValue(merge.conflicts.join(',')); c.addBindValue(QDateTime::currentSecsSinceEpoch()); c.exec(); conflicts++;
                } else if (merge.disposition == "PUSH_LOCAL" || merge.disposition == "SAFE_MERGE") enqueue(link.value(0).toString(), "UPDATE");
                continue;
            }
            QsoRecord r; r.id = QStringLiteral("wavelog-%1-%2").arg(b->id, remoteId); r.remoteId = remoteId; r.provenance = "remote";
            r.callsign = remote.fields.value("CALL", map.value("call").toString()); const QString frequency = remote.fields.value("FREQ", map.value("freq").toString());
            r.frequencyHz = frequency.toDouble() > 100000 ? frequency.toLongLong() : qRound64(frequency.toDouble() * 1000000.0);
            r.band = remote.fields.value("BAND", map.value("band").toString()); r.mode = remote.fields.value("MODE", map.value("mode").toString()); r.submode = remote.fields.value("SUBMODE");
            r.rstSent = remote.fields.value("RST_SENT", "59"); r.rstReceived = remote.fields.value("RST_RCVD", "59"); r.grid = remote.fields.value("GRIDSQUARE"); r.comment = remote.fields.value("COMMENT");
            r.stationCallsign = remote.fields.value("STATION_CALLSIGN"); r.operatorCallsign = remote.fields.value("OPERATOR"); r.dxcc = remote.fields.value("DXCC"); r.country = remote.fields.value("COUNTRY"); r.cqZone = remote.fields.value("CQZ"); r.ituZone = remote.fields.value("ITUZ"); r.contestId = remote.fields.value("CONTEST_ID");
            r.satelliteName = remote.fields.value("SAT_NAME"); r.satelliteMode = remote.fields.value("SAT_MODE"); r.propagationMode = remote.fields.value("PROP_MODE");
            r.antennaPath = remote.fields.value("ANT_PATH");
            if (remote.fields.contains("TX_PWR") && !remote.fields.value("TX_PWR").trimmed().isEmpty()) {
                bool powerOk=false; r.txPower=remote.fields.value("TX_PWR").trimmed().toDouble(&powerOk);
                if(!powerOk||!std::isfinite(r.txPower)){setState("Error");emit error(QStringLiteral("Wavelog QSO %1 has invalid TX_PWR").arg(remoteId));return;}
            }
            r.antenna = remote.fields.value("MY_ANTENNA");
            r.potaRef = remote.fields.value("POTA_REF"); r.sotaRef = remote.fields.value("SOTA_REF"); r.wwffRef = remote.fields.value("WWFF_REF"); r.iota = remote.fields.value("IOTA");
            r.qslManager = remote.fields.value("QSL_VIA"); r.qslMessage = remote.fields.value("QSLMSG"); r.qslSent = remote.fields.value("QSL_SENT"); r.qslReceived = remote.fields.value("QSL_RCVD", "N");
            r.qslSentDate = isoAdifDate(remote.fields.value("QSLSDATE")); r.qslReceivedDate = isoAdifDate(remote.fields.value("QSLRDATE")); r.qslSentMethod = remote.fields.value("QSL_SENT_VIA"); r.qslReceivedMethod = remote.fields.value("QSL_RCVD_VIA");
            for (auto it = remote.fields.cbegin(); it != remote.fields.cend(); ++it) if (!mappedWavelogFields().contains(it.key()) && safeExtraAdifName(it.key())) r.extraAdif.insert(it.key(), it.value());
            r.stationProfileId = b->localStationProfileId; r.createdAt = QDateTime::currentSecsSinceEpoch(); QString saveError;
            if (!m_database->save(r, &saveError)) { setState("Error"); emit error(QStringLiteral("Wavelog QSO %1 import failed: %2").arg(remoteId,saveError)); return; }
            QSqlQuery l(m_database->connection()); l.prepare("INSERT INTO wavelog_link(binding_id,qso_id,remote_id,baseline_json,baseline_hash,retained_fields) VALUES(?,?,?,?,?,'')"); l.addBindValue(b->id); l.addBindValue(r.id); l.addBindValue(remoteId); l.addBindValue(remote.encoded()); l.addBindValue(remote.hash());
            if(!l.exec()){setState("Error");emit error(QStringLiteral("Wavelog QSO %1 link failed: %2").arg(remoteId,safeError(l)));return;} imported++;
        }
        const auto meta = result.value("meta").toMap(); more = meta.value("has_more", false).toBool(); emit progress(pageNumber, imported, 0, conflicts); pageNumber++;
    }
    setState(conflicts ? "Conflicts require review" : "Synchronized"); emit queueChanged();
}

void WavelogSyncEngine::retryPending(){
    const auto b=binding();if(!b||!b->canWrite||!m_endpoint||!m_credentialResolver)return;const QString token=m_credentialResolver(b->credentialAlias);
    QSqlQuery q(m_database->connection());q.prepare("SELECT id,qso_id,operation,canonical_json,remote_id,attempts FROM wavelog_outbox WHERE binding_id=? AND state IN ('PENDING','RETRY_WAIT') AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY created_at LIMIT 100");q.addBindValue(b->id);q.addBindValue(QDateTime::currentSecsSinceEpoch());if(!q.exec())return;
    while(q.next()){
        CanonicalQso c;const auto obj=QJsonDocument::fromJson(q.value(3).toByteArray()).object();for(auto it=obj.begin();it!=obj.end();++it)c.fields.insert(it.key(),it.value().toString());
        const QString operation=q.value(2).toString();const auto result=m_endpoint->apply(*b,token,operation,c,q.value(4).toString());QSqlQuery update(m_database->connection());
        if(result.value("ok").toBool()){
            const QString remoteId=result.value("data").toMap().value("id",q.value(4)).toString();const CanonicalQso delivered=deliveredCanonical(c,operation);const QString retained=retainedFields(c,operation).join(',');
            auto db=m_database->connection();if(!db.transaction()){setState("Error");emit error("Unable to begin Wavelog delivery-status transaction");return;}
            update.prepare("UPDATE wavelog_outbox SET state=?,updated_at=?,remote_id=?,last_error='',retained_fields=? WHERE id=?");update.addBindValue(retained.isEmpty()?"ACCEPTED":"ACCEPTED_RETAINED");update.addBindValue(QDateTime::currentSecsSinceEpoch());update.addBindValue(remoteId);update.addBindValue(retained);update.addBindValue(q.value(0));
            if(!update.exec()){db.rollback();setState("Error");emit error(QStringLiteral("Unable to record Wavelog delivery status: %1").arg(safeError(update)));return;}
            if(!remoteId.isEmpty()&&operation!="DELETE"){QSqlQuery link(db);link.prepare("INSERT OR REPLACE INTO wavelog_link(binding_id,qso_id,remote_id,baseline_json,baseline_hash,retained_fields) VALUES(?,?,?,?,?,?)");link.addBindValue(b->id);link.addBindValue(q.value(1));link.addBindValue(remoteId);link.addBindValue(delivered.encoded());link.addBindValue(delivered.hash());link.addBindValue(retained);if(!link.exec()){db.rollback();setState("Error");emit error(QStringLiteral("Unable to record Wavelog provider baseline: %1").arg(safeError(link)));return;}}
            if(!db.commit()){db.rollback();setState("Error");emit error("Unable to commit Wavelog delivery status");return;}
        }else{
            const int status=result.value("status").toInt();const bool ambiguous=(operation=="CREATE"||operation=="DELETE")&&(status==0||status>=500);update.prepare("UPDATE wavelog_outbox SET state=?,attempts=attempts+1,next_attempt_at=?,last_error=?,updated_at=? WHERE id=?");update.addBindValue(ambiguous?"BLOCKED":((status==429||status>=500)?"RETRY_WAIT":"BLOCKED"));update.addBindValue(ambiguous?QVariant():QDateTime::currentSecsSinceEpoch()+qMin(3600,60*(1<<qMin(6,q.value(5).toInt()))));update.addBindValue(ambiguous?"Ambiguous write; reconcile before retry":result.value("error").toString().left(300));update.addBindValue(QDateTime::currentSecsSinceEpoch());update.addBindValue(q.value(0));update.exec();
        }
    }emit queueChanged();
}

bool WavelogSyncEngine::resolveConflict(const QString&id,const QString&resolution,const QVariantMap&merged){if(!QStringList{"Keep Local","Keep Remote","Merge"}.contains(resolution))return false;QSqlQuery q(m_database->connection());q.prepare("SELECT qso_id,local_json,remote_json FROM wavelog_conflict WHERE id=? AND state='OPEN'");q.addBindValue(id);if(!q.exec()||!q.next())return false;if(resolution=="Keep Local")enqueue(q.value(0).toString(),"UPDATE");else if(resolution=="Merge"){CanonicalQso c;for(auto it=merged.begin();it!=merged.end();++it)c.fields.insert(it.key().toUpper(),it.value().toString());QSqlQuery o(m_database->connection());o.prepare("UPDATE wavelog_outbox SET canonical_json=?,state='PENDING' WHERE qso_id=?");o.addBindValue(c.encoded());o.addBindValue(q.value(0));o.exec();}QSqlQuery u(m_database->connection());u.prepare("UPDATE wavelog_conflict SET state='RESOLVED',resolution_intent=? WHERE id=?");u.addBindValue(resolution);u.addBindValue(id);const bool ok=u.exec();emit queueChanged();return ok;}

} // namespace shackcq::desktop
