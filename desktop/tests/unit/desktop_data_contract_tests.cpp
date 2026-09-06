#include "shackcq/desktop/AdifService.hpp"
#include "shackcq/desktop/QsoDatabase.hpp"
#include "shackcq/desktop/WavelogSync.hpp"

#include <QElapsedTimer>
#include <QFile>
#include <QJsonDocument>
#include <QSqlError>
#include <QSqlQuery>
#include <QTemporaryDir>
#include <QtTest>

using namespace shackcq::desktop;

class DesktopDataContractTests final : public QObject {
    Q_OBJECT
private slots:
    void schemaMigrationAndProjectionTruth() {
        QTemporaryDir dir; QsoDatabase database(dir.filePath("qso.sqlite")); QString error;
        QVERIFY2(database.open(&error), qPrintable(error));
        QSqlQuery version(database.connection()); QVERIFY(version.exec("PRAGMA user_version")); QVERIFY(version.next()); QCOMPARE(version.value(0).toInt(), 16);
        QsoRecord q; q.callsign="om0rx"; q.frequencyHz=14074000; q.mode="FT8"; q.grid="JN88TQ"; q.extraAdif.insert("APP_EXAMPLE","preserved");
        QVERIFY2(database.save(q,&error),qPrintable(error)); QCOMPARE(database.count(),1); QVERIFY2(database.verifyProjection(&error),qPrintable(error));
        const auto worked=database.workedConfirmed("OM0RX","20m","FT8"); QVERIFY(worked.value("worked").toBool()); QVERIFY(!worked.value("confirmed").toBool());
    }
    void adifUnknownFieldRoundTrip() {
        QsoRecord q; q.callsign="VK9XX";q.frequencyHz=14074000;q.band="20m";q.mode="FT8";q.createdAt=1777000000;q.extraAdif.insert("X_PRIVATE_EXTENSION","round-trip");
        QString error;const auto parsed=AdifService::parseRecord(AdifService::serialize(q),&error);QVERIFY2(parsed.has_value(),qPrintable(error));QCOMPARE(parsed->callsign,QString("VK9XX"));QCOMPARE(parsed->extraAdif.value("X_PRIVATE_EXTENSION").toString(),QString("round-trip"));
    }
    void hundredThousandKeysetPaging() {
        QTemporaryDir dir;QsoDatabase database(dir.filePath("scale.sqlite"));QString error;QVERIFY(database.open(&error));auto db=database.connection();QVERIFY(db.transaction());QSqlQuery q(db);q.prepare("INSERT INTO qso(id,callsign,frequency_hz,band,mode,created_at,updated_at) VALUES(?,?,?,?,?,?,?)");
        for(int i=0;i<100000;i++){q.addBindValue(QString("fixture-%1").arg(i));q.addBindValue(QString("T%1").arg(i));q.addBindValue(14000000+i);q.addBindValue("20m");q.addBindValue("CW");q.addBindValue(1700000000+i);q.addBindValue(1700000000+i);QVERIFY2(q.exec(),qPrintable(q.lastError().text()));q.finish();}QVERIFY(db.commit());
        QElapsedTimer timer;timer.start();QsoQuery query;query.limit=250;const auto page=database.page(query,&error);QCOMPARE(page.size(),250);QVERIFY2(timer.elapsed()<2000,qPrintable(QString("Page took %1 ms").arg(timer.elapsed())));QCOMPARE(database.count(),100000);
    }
    void threeWayConflictAndSafeMerge() {
        CanonicalQso base{{{"CALL","OM0RX"},{"MODE","CW"}}};CanonicalQso local=base;local.fields["MODE"]="FT8";CanonicalQso remote=base;remote.fields["GRIDSQUARE"]="JN88TQ";
        const auto safe=WavelogSyncEngine::threeWayMerge(base,local,remote);QCOMPARE(safe.disposition,QString("SAFE_MERGE"));QVERIFY(safe.conflicts.isEmpty());
        remote.fields["MODE"]="SSB";const auto conflict=WavelogSyncEngine::threeWayMerge(base,local,remote);QCOMPARE(conflict.disposition,QString("CONFLICT"));QVERIFY(conflict.conflicts.contains("MODE"));
    }
    void sharedSchema16GoldenFixtureMatchesDesktopSemantics() {
        QFile file(QStringLiteral(SHACKCQ_SHARED_FIXTURES_DIR "/schema16_qso_golden.json"));QVERIFY(file.open(QIODevice::ReadOnly));const auto object=QJsonDocument::fromJson(file.readAll()).object();QCOMPARE(object.value("schemaVersion").toInt(),QsoDatabase::SchemaVersion);const auto adif=object.value("canonicalAdif").toObject();QCOMPARE(adif.value("CALL").toString(),QString("VK9XX"));QCOMPARE(adif.value("APP_SHACKCQ_FUTURE").toString(),QString("preserved"));const auto expectations=object.value("expectations").toObject();QVERIFY(expectations.value("semanticInteroperabilityOnly").toBool());QVERIFY(!expectations.value("androidWindowsDatabaseBytesInterchangeable").toBool());
    }
};
QTEST_MAIN(DesktopDataContractTests)
#include "desktop_data_contract_tests.moc"
