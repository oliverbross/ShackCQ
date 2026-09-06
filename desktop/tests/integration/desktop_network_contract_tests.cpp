#include "shackcq/desktop/ClusterController.hpp"
#include "shackcq/desktop/DesktopPlatform.hpp"
#include "shackcq/desktop/WavelogSync.hpp"

#include <QTemporaryDir>
#include <QtTest>

using namespace shackcq::desktop;

class FakeWavelogEndpoint final : public WavelogEndpoint {
public:
    int pageCalls{};
    int applyCalls{};
    int closeCalls{};
    void close() override { closeCalls++; }
    QVariantMap capabilities(const QUrl&,const QString&)override{return{{"ok",true},{"scopes",QStringList{"qso:read","qso:write"}}};}
    QVariantList stations(const QUrl&,const QString&)override{return{QVariantMap{{"id","7"},{"name","Home"}}};}
    QVariantMap page(const WavelogBinding&,const QString&,int page)override{pageCalls++;return{{"ok",true},{"data",page==1?QVariantList{QVariantMap{{"id","99"},{"CALL","VK9XX"},{"FREQ","14.074"},{"BAND","20m"},{"MODE","FT8"}}}:QVariantList{}},{"meta",QVariantMap{{"has_more",false}}}};}
    QVariantMap apply(const WavelogBinding&,const QString&,const QString&,const CanonicalQso&,const QString&)override{applyCalls++;return{{"ok",true},{"data",QVariantMap{{"id","100"}}}};}
};

class DesktopNetworkContractTests final : public QObject {
    Q_OBJECT
private slots:
    void clusterFixtureFeedsOneRepositoryAndDeduplicates() {
        SpotRepository repository;ClusterController cluster(&repository);cluster.ingestFixtureLine("DX de K1ABC: 14074.0 VK9XX FT8 strong",1777000000);cluster.ingestFixtureLine("DX de K1ABC: 14074.0 VK9XX FT8 stronger",1777000001);QCOMPARE(repository.rowCount(),1);QCOMPARE(repository.exact(0).value("callsign").toString(),QString("VK9XX"));
    }
    void fakeServiceInitialSyncAndRestartSafeStore() {
        QTemporaryDir dir;QsoDatabase database(dir.filePath("qso.sqlite"));QString error;QVERIFY(database.open(&error));FakeWavelogEndpoint endpoint;FakeCredentialVault vault;QVERIFY(vault.write("alias","test","wl2_fixture"));WavelogSyncEngine engine(&database);engine.setEndpoint(&endpoint);engine.setCredentialResolver([&](const QString&a){return vault.read(a).value_or(QString{});});QVERIFY(engine.saveBinding({"binding",QUrl("https://example.test"),"alias","local","7",true,true},&error));engine.synchronize("INITIAL");QCOMPARE(database.count(),1);QCOMPARE(engine.state(),QString("Synchronized"));
    }
    void closeIsIdempotentAndRejectsLateWork() {
        QTemporaryDir dir;QsoDatabase database(dir.filePath("qso.sqlite"));QString error;QVERIFY(database.open(&error));FakeWavelogEndpoint endpoint;FakeCredentialVault vault;QVERIFY(vault.write("alias","test","wl2_fixture"));WavelogSyncEngine engine(&database);engine.setEndpoint(&endpoint);engine.setCredentialResolver([&](const QString&a){return vault.read(a).value_or(QString{});});QVERIFY(engine.saveBinding({"binding",QUrl("https://example.test"),"alias","local","7",true,true},&error));engine.close();engine.close();QVERIFY(engine.closed());QCOMPARE(engine.state(),QString("Closed"));QCOMPARE(endpoint.closeCalls,1);engine.synchronize("INITIAL");QCOMPARE(endpoint.pageCalls,0);QCOMPARE(endpoint.applyCalls,0);
    }
};
QTEST_MAIN(DesktopNetworkContractTests)
#include "desktop_network_contract_tests.moc"
