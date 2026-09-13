#include "shackcq/desktop/ClusterController.hpp"
#include "shackcq/desktop/DesktopPlatform.hpp"
#include "shackcq/desktop/WavelogSync.hpp"

#include <QTemporaryDir>
#include <QJsonDocument>
#include <QSqlQuery>
#include <QtTest>
#include <cmath>

using namespace shackcq::desktop;

class FakeWavelogEndpoint final : public WavelogEndpoint {
public:
    int pageCalls{};
    int applyCalls{};
    int closeCalls{};
    QVariantList rowsOverride;
    void close() override { closeCalls++; }
    QVariantMap capabilities(const QUrl&,const QString&)override{return{{"ok",true},{"scopes",QStringList{"qso:read","qso:write"}}};}
    QVariantList stations(const QUrl&,const QString&)override{return{QVariantMap{{"id","7"},{"name","Home"}}};}
    QVariantMap page(const WavelogBinding&,const QString&,int page)override{pageCalls++;const QVariantList defaults{QVariantMap{{"id","99"},{"CALL","VK9XX"},{"FREQ","145.800"},{"BAND","2m"},{"MODE","FM"},{"SAT_NAME","QO-100"},{"SAT_MODE","S"},{"PROP_MODE","FUTURE"},{"ANT_PATH","G"},{"TX_PWR","5.5"},{"MY_ANTENNA","Portable whip"},{"POTA_REF","VK-0001,VK-0002"},{"SOTA_REF","VK1/AC-001,VK2/SM-002"},{"WWFF_REF","VKFF-0001,VKFF-0002"},{"IOTA","OC-001,OC-002"},{"QSL_VIA","VK1QSL"},{"QSLMSG","Thanks"},{"QSL_SENT","Y"},{"QSL_RCVD","Y"},{"QSLSDATE","20260911"},{"QSLRDATE","20260912"},{"QSL_SENT_VIA","E"},{"QSL_RCVD_VIA","D"},{"APP_FUTURE","keep"},{"API_TOKEN","must-not-leak"},{"APP_SHACKCQ_OPERATION_ID","must-not-leak"}}};return{{"ok",true},{"data",page==1?(rowsOverride.isEmpty()?defaults:rowsOverride):QVariantList{}},{"meta",QVariantMap{{"has_more",false}}}};}
    QVariantMap apply(const WavelogBinding&,const QString&,const QString&,const CanonicalQso&,const QString&)override{applyCalls++;return{{"ok",true},{"data",QVariantMap{{"id","100"}}}};}
};

class DesktopNetworkContractTests final : public QObject {
    Q_OBJECT
private slots:
    void clusterFixtureFeedsOneRepositoryAndDeduplicates() {
        SpotRepository repository;ClusterController cluster(&repository);cluster.ingestFixtureLine("DX de K1ABC: 14074.0 VK9XX FT8 strong",1777000000);cluster.ingestFixtureLine("DX de K1ABC: 14074.0 VK9XX FT8 stronger",1777000001);QCOMPARE(repository.rowCount(),1);QCOMPARE(repository.exact(0).value("callsign").toString(),QString("VK9XX"));
    }
    void fakeServiceInitialSyncAndRestartSafeStore() {
        QTemporaryDir dir;QsoDatabase database(dir.filePath("qso.sqlite"));QString error;QVERIFY(database.open(&error));FakeWavelogEndpoint endpoint;FakeCredentialVault vault;QVERIFY(vault.write("alias","test","wl2_fixture"));WavelogSyncEngine engine(&database);engine.setEndpoint(&endpoint);engine.setCredentialResolver([&](const QString&a){return vault.read(a).value_or(QString{});});QVERIFY(engine.saveBinding({"binding",QUrl("https://example.test"),"alias","local","7",true,true},&error));engine.synchronize("INITIAL");QCOMPARE(database.count(),1);QCOMPARE(engine.state(),QString("Synchronized"));const auto imported=database.page({}).first();QCOMPARE(imported.propagationMode,QString("FUTURE"));QCOMPARE(imported.antennaPath,QString("G"));QCOMPARE(imported.txPower,5.5);QCOMPARE(imported.antenna,QString("Portable whip"));QCOMPARE(imported.sotaRef,QString("VK1/AC-001,VK2/SM-002"));QCOMPARE(imported.wwffRef,QString("VKFF-0001,VKFF-0002"));QCOMPARE(imported.iota,QString("OC-001,OC-002"));QCOMPARE(imported.qslManager,QString("VK1QSL"));QCOMPARE(imported.qslMessage,QString("Thanks"));QCOMPARE(imported.qslSentDate,QString("2026-09-11"));QCOMPARE(imported.qslReceivedDate,QString("2026-09-12"));QCOMPARE(imported.qslSentMethod,QString("E"));QCOMPARE(imported.qslReceivedMethod,QString("D"));QCOMPARE(imported.extraAdif.value("APP_FUTURE").toString(),QString("keep"));QVERIFY(!imported.extraAdif.contains("API_TOKEN"));QVERIFY(!imported.extraAdif.contains("APP_SHACKCQ_OPERATION_ID"));
    }
    void malformedWavelogPowerFailsSynchronizationTruthfully() {
        QTemporaryDir dir;QsoDatabase database(dir.filePath("invalid.sqlite"));QString error;QVERIFY(database.open(&error));FakeWavelogEndpoint endpoint;endpoint.rowsOverride={QVariantMap{{"id","bad-power"},{"CALL","VK9XX"},{"FREQ","14.074"},{"BAND","20m"},{"MODE","FT8"},{"TX_PWR","watts"}}};FakeCredentialVault vault;QVERIFY(vault.write("alias","test","wl2_fixture"));WavelogSyncEngine engine(&database);engine.setEndpoint(&endpoint);engine.setCredentialResolver([&](const QString&a){return vault.read(a).value_or(QString{});});QVERIFY(engine.saveBinding({"binding",QUrl("https://example.test"),"alias","local","7",true,true},&error));QSignalSpy errors(&engine,&WavelogSyncEngine::error);engine.synchronize("INITIAL");QCOMPARE(engine.state(),QString("Error"));QCOMPARE(database.count(),0);QCOMPARE(errors.size(),1);QVERIFY(errors.first().first().toString().contains("invalid TX_PWR"));
    }
    void emptyWavelogPowerRemainsUnset() {
        QTemporaryDir dir;QsoDatabase database(dir.filePath("empty-power.sqlite"));QString error;QVERIFY(database.open(&error));FakeWavelogEndpoint endpoint;endpoint.rowsOverride={QVariantMap{{"id","empty-power"},{"CALL","VK9XX"},{"FREQ","14.074"},{"BAND","20m"},{"MODE","FT8"},{"TX_PWR",""}}};FakeCredentialVault vault;QVERIFY(vault.write("alias","test","wl2_fixture"));WavelogSyncEngine engine(&database);engine.setEndpoint(&endpoint);engine.setCredentialResolver([&](const QString&a){return vault.read(a).value_or(QString{});});QVERIFY(engine.saveBinding({"binding",QUrl("https://example.test"),"alias","local","7",true,true},&error));engine.synchronize("INITIAL");QCOMPARE(engine.state(),QString("Synchronized"));QCOMPARE(database.count(),1);QVERIFY(std::isnan(database.page({}).first().txPower));
    }
    void createPatchBodiesAndRetainedFieldsAreTruthful() {
        CanonicalQso canonical{{{"CALL","OM0RX"},{"FREQ","14.074000"},{"QSO_DATE","20260913"},{"TIME_ON","010203"},{"PROP_MODE","SAT"},{"SOTA_REF","W1/AA-001,W2/BB-002"},{"WWFF_REF","VKFF-0001,VKFF-0002"},{"IOTA","OC-001,OC-002"},{"ANT_PATH","G"},{"MY_ANTENNA","Yagi"},{"QSLMSG","Thanks"},{"QSL_SENT","Y"},{"QSL_RCVD","Y"},{"QSLSDATE","20260912"},{"QSLRDATE","20260913"},{"QSL_SENT_VIA","E"},{"QSL_RCVD_VIA","D"}}};const WavelogBinding binding{"binding",QUrl("https://example.test"),"alias","local","7",true,true};const auto create=QtWavelogEndpoint::requestBody(binding,canonical,"CREATE");const auto patch=QtWavelogEndpoint::requestBody(binding,canonical,"UPDATE");QVERIFY(create.contains("ant_path"));QVERIFY(create.contains("qsl_sent_via"));QCOMPARE(create.value("sota_ref").toString(),QString("W1/AA-001,W2/BB-002"));QCOMPARE(patch.value("wwff_ref").toString(),QString("VKFF-0001,VKFF-0002"));QCOMPARE(patch.value("iota").toString(),QString("OC-001,OC-002"));QVERIFY(patch.contains("prop_mode"));QVERIFY(!patch.contains("ant_path"));QVERIFY(!patch.contains("qsl_sent_via"));
        QTemporaryDir dir;QsoDatabase database(dir.filePath("retained.sqlite"));QString error;QVERIFY(database.open(&error));QsoRecord q;q.id="local";q.remoteId="99";q.callsign="OM0RX";q.frequencyHz=14074000;q.mode="FT8";q.propagationMode="SAT";q.antennaPath="G";q.antenna="Yagi";q.qslMessage="Thanks";q.qslSent="Y";q.qslReceived="Y";q.qslSentDate="2026-09-12";q.qslReceivedDate="2026-09-13";q.qslSentMethod="E";q.qslReceivedMethod="D";QVERIFY2(database.save(q,&error),qPrintable(error));FakeWavelogEndpoint endpoint;FakeCredentialVault vault;QVERIFY(vault.write("alias","test","wl2_fixture"));WavelogSyncEngine engine(&database);engine.setEndpoint(&endpoint);engine.setCredentialResolver([&](const QString&a){return vault.read(a).value_or(QString{});});QVERIFY(engine.saveBinding(binding,&error));QVERIFY(engine.enqueue(q.id,"UPDATE",&error));engine.retryPending();QSqlQuery outbox(database.connection());QVERIFY(outbox.exec("SELECT state,retained_fields FROM wavelog_outbox WHERE qso_id='local'"));QVERIFY(outbox.next());QCOMPARE(outbox.value(0).toString(),QString("ACCEPTED_RETAINED"));const QStringList retained=outbox.value(1).toString().split(',');for(const QString &field:QStringList{"ANT_PATH","MY_ANTENNA","QSLMSG","QSL_SENT","QSL_RCVD","QSLSDATE","QSLRDATE","QSL_SENT_VIA","QSL_RCVD_VIA"})QVERIFY(retained.contains(field));QSqlQuery link(database.connection());QVERIFY(link.exec("SELECT baseline_json,retained_fields FROM wavelog_link WHERE qso_id='local'"));QVERIFY(link.next());const auto baseline=QJsonDocument::fromJson(link.value(0).toByteArray()).object();QVERIFY(baseline.contains("PROP_MODE"));QVERIFY(!baseline.contains("ANT_PATH"));QCOMPARE(link.value(1).toString(),outbox.value(1).toString());
    }
    void closeIsIdempotentAndRejectsLateWork() {
        QTemporaryDir dir;QsoDatabase database(dir.filePath("qso.sqlite"));QString error;QVERIFY(database.open(&error));FakeWavelogEndpoint endpoint;FakeCredentialVault vault;QVERIFY(vault.write("alias","test","wl2_fixture"));WavelogSyncEngine engine(&database);engine.setEndpoint(&endpoint);engine.setCredentialResolver([&](const QString&a){return vault.read(a).value_or(QString{});});QVERIFY(engine.saveBinding({"binding",QUrl("https://example.test"),"alias","local","7",true,true},&error));engine.close();engine.close();QVERIFY(engine.closed());QCOMPARE(engine.state(),QString("Closed"));QCOMPARE(endpoint.closeCalls,1);engine.synchronize("INITIAL");QCOMPARE(endpoint.pageCalls,0);QCOMPARE(endpoint.applyCalls,0);
    }
};
QTEST_MAIN(DesktopNetworkContractTests)
#include "desktop_network_contract_tests.moc"
