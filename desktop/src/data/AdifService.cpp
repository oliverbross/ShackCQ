#include "shackcq/desktop/AdifService.hpp"

#include <QDateTime>
#include <QFile>
#include <QRegularExpression>
#include <QSaveFile>
#include <QTimeZone>
#include <QUuid>

namespace shackcq::desktop {
namespace {

QByteArray field(const QByteArray &name, const QString &value) {
    const QByteArray bytes = value.toUtf8();
    return bytes.isEmpty() ? QByteArray{} : QByteArray("<") + name + ':' + QByteArray::number(bytes.size()) + '>' + bytes;
}

QMap<QString, QString> fields(const QByteArray &record, QString *error) {
    QMap<QString, QString> result;
    qsizetype pos = 0;
    while (pos < record.size()) {
        const qsizetype open = record.indexOf('<', pos);
        if (open < 0) break;
        const qsizetype close = record.indexOf('>', open + 1);
        if (close < 0) { if (error) *error = QStringLiteral("Unterminated ADIF field"); return {}; }
        const QList<QByteArray> descriptor = record.mid(open + 1, close - open - 1).split(':');
        const QString name = QString::fromLatin1(descriptor.value(0)).trimmed().toUpper();
        if (name == QStringLiteral("EOR") || name == QStringLiteral("EOH")) { pos = close + 1; continue; }
        bool ok = false;
        const int length = descriptor.value(1).toInt(&ok);
        if (!ok || length < 0 || length > 1048576 || close + 1 + length > record.size()) {
            if (error) *error = QStringLiteral("Invalid or oversized ADIF field length");
            return {};
        }
        result.insert(name, QString::fromUtf8(record.mid(close + 1, length)));
        pos = close + 1 + length;
    }
    return result;
}

qint64 frequencyHz(const QString &mhz) {
    bool ok = false;
    const double value = mhz.toDouble(&ok);
    return ok ? qRound64(value * 1000000.0) : 0;
}

} // namespace

AdifService::AdifService(QsoDatabase *database, QObject *parent) : QObject(parent), m_database(database) {}

QByteArray AdifService::serialize(const QsoRecord &record) {
    const QDateTime utc = QDateTime::fromSecsSinceEpoch(record.createdAt, QTimeZone::UTC);
    QByteArray out;
    out += field("QSO_DATE", utc.toString(QStringLiteral("yyyyMMdd")));
    out += field("TIME_ON", utc.toString(QStringLiteral("HHmmss")));
    out += field("CALL", record.callsign);
    out += field("FREQ", QString::number(record.frequencyHz / 1000000.0, 'f', 6));
    out += field("BAND", record.band); out += field("MODE", record.mode); out += field("SUBMODE", record.submode);
    out += field("RST_SENT", record.rstSent); out += field("RST_RCVD", record.rstReceived);
    out += field("GRIDSQUARE", record.grid); out += field("COMMENT", record.comment);
    out += field("STATION_CALLSIGN", record.stationCallsign); out += field("OPERATOR", record.operatorCallsign);
    out += field("DXCC", record.dxcc); out += field("COUNTRY", record.country); out += field("CQZ", record.cqZone); out += field("ITUZ", record.ituZone);
    out += field("CONTEST_ID", record.contestId); out += field("SAT_NAME", record.satelliteName); out += field("SAT_MODE", record.satelliteMode);
    out += field("POTA_REF", record.potaRef); out += field("SOTA_REF", record.sotaRef); out += field("IOTA", record.iota); out += field("WWFF_REF", record.wwffRef);
    out += field("QSL_RCVD", record.qslReceived); out += field("LOTW_QSL_RCVD", record.lotwReceived);
    out += field("EQSL_QSL_RCVD", record.eqslReceived); out += field("APP_SHACKCQ_QRZ_RCVD", record.qrzReceived);
    static const QSet<QString> reserved{"QSO_DATE","TIME_ON","CALL","FREQ","BAND","MODE","SUBMODE","RST_SENT","RST_RCVD","GRIDSQUARE","COMMENT","STATION_CALLSIGN","OPERATOR","DXCC","COUNTRY","CQZ","ITUZ","CONTEST_ID","SAT_NAME","SAT_MODE","POTA_REF","SOTA_REF","IOTA","WWFF_REF","QSL_RCVD","LOTW_QSL_RCVD","EQSL_QSL_RCVD","APP_SHACKCQ_QRZ_RCVD"};
    QStringList names = record.extraAdif.keys(); names.sort();
    for (const auto &name : names) if (!reserved.contains(name.toUpper())) out += field(name.toUpper().toLatin1(), record.extraAdif.value(name).toString());
    out += "<EOR>\r\n";
    return out;
}

std::optional<QsoRecord> AdifService::parseRecord(const QByteArray &record, QString *error) {
    const auto map = fields(record, error);
    if (map.isEmpty() || map.value("CALL").isEmpty()) { if (error && error->isEmpty()) *error = QStringLiteral("ADIF record has no CALL"); return std::nullopt; }
    QsoRecord q;
    q.id = QUuid::createUuid().toString(QUuid::WithoutBraces); q.callsign = map.value("CALL");
    q.frequencyHz = frequencyHz(map.value("FREQ")); q.frequencyRxHz = frequencyHz(map.value("FREQ_RX"));
    q.band = map.value("BAND"); q.bandRx = map.value("BAND_RX"); q.mode = map.value("MODE"); q.submode = map.value("SUBMODE");
    q.rstSent = map.value("RST_SENT", "59"); q.rstReceived = map.value("RST_RCVD", "59"); q.grid = map.value("GRIDSQUARE"); q.comment = map.value("COMMENT");
    q.stationCallsign = map.value("STATION_CALLSIGN"); q.operatorCallsign = map.value("OPERATOR"); q.dxcc = map.value("DXCC"); q.country = map.value("COUNTRY");
    q.cqZone = map.value("CQZ"); q.ituZone = map.value("ITUZ"); q.contestId = map.value("CONTEST_ID"); q.satelliteName = map.value("SAT_NAME"); q.satelliteMode = map.value("SAT_MODE");
    q.potaRef = map.value("POTA_REF"); q.sotaRef = map.value("SOTA_REF"); q.iota = map.value("IOTA"); q.wwffRef = map.value("WWFF_REF");
    q.qslReceived = map.value("QSL_RCVD", "N"); q.lotwReceived = map.value("LOTW_QSL_RCVD", "N"); q.eqslReceived = map.value("EQSL_QSL_RCVD", "N"); q.qrzReceived = map.value("APP_SHACKCQ_QRZ_RCVD", "N");
    q.provenance = QStringLiteral("import");
    QDate date = QDate::fromString(map.value("QSO_DATE"), QStringLiteral("yyyyMMdd"));
    QString timeValue = map.value("TIME_ON").left(6).leftJustified(6, '0');
    QTime time = QTime::fromString(timeValue, QStringLiteral("HHmmss"));
    q.createdAt = date.isValid() && time.isValid() ? QDateTime(date, time, QTimeZone::UTC).toSecsSinceEpoch() : QDateTime::currentSecsSinceEpoch();
    static const QSet<QString> known{"QSO_DATE","TIME_ON","CALL","FREQ","FREQ_RX","BAND","BAND_RX","MODE","SUBMODE","RST_SENT","RST_RCVD","GRIDSQUARE","COMMENT","STATION_CALLSIGN","OPERATOR","DXCC","COUNTRY","CQZ","ITUZ","CONTEST_ID","SAT_NAME","SAT_MODE","POTA_REF","SOTA_REF","IOTA","WWFF_REF","QSL_RCVD","LOTW_QSL_RCVD","EQSL_QSL_RCVD","APP_SHACKCQ_QRZ_RCVD"};
    for (auto it = map.cbegin(); it != map.cend(); ++it) if (!known.contains(it.key())) q.extraAdif.insert(it.key(), it.value());
    if (q.frequencyHz <= 0 || q.mode.isEmpty()) { if (error) *error = QStringLiteral("ADIF record requires FREQ and MODE"); return std::nullopt; }
    return q;
}

bool AdifService::importFile(const QString &path) {
    if (m_busy) {
        return false;
    }
    m_busy = true;
    m_cancelled = false;
    emit busyChanged();
    QFile file(path); if (!file.open(QIODevice::ReadOnly)) { m_busy=false;emit busyChanged();emit finished(false,file.errorString());return false; }
    QByteArray buffer; qint64 imported=0; QString failure;
    while (!file.atEnd() && !m_cancelled) {
        buffer += file.read(65536);
        if (buffer.size() > 2097152 && buffer.toUpper().indexOf("<EOR>") < 0) { failure="ADIF record exceeds 2 MiB bound"; break; }
        while (true) {
            const qsizetype marker = buffer.toUpper().indexOf("<EOR>");
            if (marker < 0) break;
            const qsizetype end = marker + 5; const QByteArray bytes = buffer.left(end); buffer.remove(0,end);
            QString error; const auto qso=parseRecord(bytes,&error); if(!qso||!m_database->save(*qso,&error)){failure=error;break;} imported++; emit progress(imported,file.size());
        }
        if(!failure.isEmpty())break;
    }
    const bool ok=failure.isEmpty()&&!m_cancelled; m_busy=false;emit busyChanged();emit finished(ok,m_cancelled?"Import cancelled":(ok?QStringLiteral("Imported %1 QSOs").arg(imported):failure));return ok;
}

bool AdifService::exportFile(const QString &path,const QString &stationProfileId) {
    if (m_busy) {
        return false;
    }
    m_busy=true;m_cancelled=false;emit busyChanged();QSaveFile file(path);if(!file.open(QIODevice::WriteOnly)){m_busy=false;emit busyChanged();emit finished(false,file.errorString());return false;}
    file.write("Generated by ShackCQ Windows Desktop Alpha <ADIF_VER:5>3.1.4<EOH>\r\n");QsoQuery query;query.stationProfileId=stationProfileId;query.limit=250;qint64 exported=0;const int total=m_database->count(query);
    while(!m_cancelled){QString error;const auto rows=m_database->page(query,&error);if(!error.isEmpty()){file.cancelWriting();m_busy=false;emit busyChanged();emit finished(false,error);return false;}if(rows.isEmpty())break;for(const auto&r:rows){if(m_cancelled)break;file.write(serialize(r));exported++;}const auto&last=rows.last();query.cursorCreatedAt=last.createdAt;query.cursorId=last.id;emit progress(exported,total);}
    const bool ok=!m_cancelled&&file.commit();if(!ok)file.cancelWriting();m_busy=false;emit busyChanged();emit finished(ok,m_cancelled?"Export cancelled":(ok?QStringLiteral("Exported %1 QSOs").arg(exported):file.errorString()));return ok;
}

void AdifService::cancel(){m_cancelled=true;}

} // namespace shackcq::desktop
