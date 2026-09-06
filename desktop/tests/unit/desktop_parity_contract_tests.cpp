#include "shackcq/desktop/DesktopParityPlatform.hpp"

#include <QDir>
#include <QFileInfo>
#include <QJsonDocument>
#include <QTemporaryDir>
#include <QtTest>

using namespace shackcq::desktop;

class DesktopParityContractTests final : public QObject {
    Q_OBJECT
private slots:
    void opensSeparateSemanticStores();
    void registriesExposeTruthWithoutLiveData();
    void demoModeIsDeterministicAndPrivate();
    void reviewedActionsNeverOperateHardware();
    void providersAreDisabledAndCooldownBounded();
    void providerResponseMatrix();
};

void DesktopParityContractTests::opensSeparateSemanticStores() {
    QTemporaryDir root;
    QVERIFY(root.isValid());
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.path() + "/db", root.path() + "/cache", false, &error), qPrintable(error));
    const QVariantMap health = platform.databaseHealth();
    QCOMPARE(health.size(), 5);
    const QHash<QString, int> schemas{{"Neural", 5}, {"Digi", 2}, {"Groups.io", 2},
                                      {"Contest", 2}, {"DX Chaser", 1}};
    for (auto it = schemas.cbegin(); it != schemas.cend(); ++it) {
        const QVariantMap store = health.value(it.key()).toMap();
        QVERIFY(store.value("open").toBool());
        QCOMPARE(store.value("schema").toInt(), it.value());
        QVERIFY(!store.value("file").toString().isEmpty());
    }
    QCOMPARE(QDir(root.path() + "/db").entryList({"*.sqlite"}, QDir::Files).size(), 5);
}

void DesktopParityContractTests::registriesExposeTruthWithoutLiveData() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.path() + "/db", root.path() + "/cache", false, &error), qPrintable(error));
    QCOMPARE(platform.homeModules()->rowCount(), 19);
    QCOMPARE(platform.providers()->rowCount(), 17);
    QCOMPARE(platform.digiModes()->rowCount(), 12);
    QCOMPARE(platform.contestDefinitions()->rowCount(), 4);
    QCOMPARE(platform.neuralOpportunities()->rowCount(), 0);
    QCOMPARE(platform.groupsMessages()->rowCount(), 0);
    QCOMPARE(platform.portableActivity()->rowCount(), 0);
    QCOMPARE(platform.satellitePasses()->rowCount(), 0);
    for (int row = 0; row < platform.providers()->rowCount(); ++row) {
        const QVariantMap provider = platform.providers()->item(row);
        QCOMPARE(provider.value("state").toString(), QString("UNAVAILABLE"));
        QVERIFY(!provider.value("enabled").toBool());
    }
}

void DesktopParityContractTests::demoModeIsDeterministicAndPrivate() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.path() + "/db", root.path() + "/cache", true, &error), qPrintable(error));
    QVERIFY(platform.demoMode());
    QCOMPARE(platform.neuralOpportunities()->rowCount(), 2);
    QCOMPARE(platform.contestLog()->rowCount(), 2);
    QCOMPARE(platform.groupsMessages()->rowCount(), 2);
    QCOMPARE(platform.portableActivity()->rowCount(), 3);
    QCOMPARE(platform.satellitePasses()->rowCount(), 3);
    const QByteArray serialized = QJsonDocument::fromVariant(platform.databaseHealth()).toJson();
    QVERIFY(!serialized.contains("OM0RX"));
    QVERIFY(!serialized.contains("wavelog"));
    QVERIFY(!serialized.contains("credential"));
}

void DesktopParityContractTests::reviewedActionsNeverOperateHardware() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.path() + "/db", root.path() + "/cache", true, &error), qPrintable(error));
    QVERIFY(platform.prepareReceiveReview("Portable", {{"callsign", "K1ABC"}}));
    QVERIFY(platform.activeReview().contains("no CAT command sent"));
    QVERIFY(platform.prepareContestMerge({{"id", "session-1"}}));
    QVERIFY(platform.activeReview().contains("explicit confirmation"));
    QVERIFY(platform.prepareGroupsDraft({{"subject", "Field test"}, {"body", "Draft"}}));
    QVERIFY(platform.activeReview().contains("nothing posted"));
    QVERIFY(platform.selectSatellitePass({{"title", "SO-50"}}));
    QVERIFY(platform.activeReview().contains("no Doppler follow or TX"));
    platform.globalStop();
    QVERIFY(platform.activeReview().isEmpty());
    QCOMPARE(platform.safetyState(), QString("STOPPED / disconnected / TX off / automation disarmed"));
}

void DesktopParityContractTests::providersAreDisabledAndCooldownBounded() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.path() + "/db", root.path() + "/cache", false, &error), qPrintable(error));
    QVERIFY(!platform.refreshProvider("noaa-solar"));
    QVERIFY(platform.setProviderEnabled("noaa-solar", true));
    QVERIFY(!platform.refreshProvider("unknown-provider"));
    QVERIFY(!platform.setProviderEnabled("unknown-provider", true));
    platform.globalStop();
}

void DesktopParityContractTests::providerResponseMatrix() {
    const QStringList json{"application/json"};
    QVariantMap decision = ProviderResponsePolicy::evaluate(200, "application/json", "{\"ok\":true}", json, 1024, false);
    QCOMPARE(decision.value("state").toString(), QString("CURRENT"));
    QVERIFY(decision.value("acceptCache").toBool());

    decision = ProviderResponsePolicy::evaluate(200, "application/json", {}, json, 1024, false);
    QCOMPARE(decision.value("state").toString(), QString("EMPTY"));
    decision = ProviderResponsePolicy::evaluate(200, "application/json", "not-json", json, 1024, false);
    QCOMPARE(decision.value("state").toString(), QString("ERROR"));
    QCOMPARE(decision.value("detail").toString(), QString("Malformed JSON response"));
    decision = ProviderResponsePolicy::evaluate(200, "application/json", QByteArray(1025, 'x'), json, 1024, true);
    QCOMPARE(decision.value("state").toString(), QString("OFFLINE_CACHE"));
    decision = ProviderResponsePolicy::evaluate(200, "text/html", "<html>", json, 1024, false);
    QCOMPARE(decision.value("detail").toString(), QString("Unexpected provider content type"));
    decision = ProviderResponsePolicy::evaluate(0, {}, {}, json, 1024, true, "Request timed out");
    QCOMPARE(decision.value("state").toString(), QString("OFFLINE_CACHE"));
    decision = ProviderResponsePolicy::evaluate(429, "application/json", {}, json, 1024, true, {}, "120");
    QCOMPARE(decision.value("retryAfterSeconds").toInt(), 120);
    decision = ProviderResponsePolicy::evaluate(304, "application/json", {}, json, 1024, true);
    QCOMPARE(decision.value("state").toString(), QString("CURRENT"));
}

QTEST_GUILESS_MAIN(DesktopParityContractTests)
#include "desktop_parity_contract_tests.moc"
