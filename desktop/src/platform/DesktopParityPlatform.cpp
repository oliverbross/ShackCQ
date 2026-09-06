#include "shackcq/desktop/DesktopParityPlatform.hpp"

#include <QDateTime>
#include <QDir>
#include <QElapsedTimer>
#include <QFileInfo>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSqlError>
#include <QSqlQuery>
#include <QUuid>

namespace shackcq::desktop {
namespace {

QVariantMap row(QString key, QString title, QString subtitle, QString state,
                QString detail = {}, QString category = {}, QVariant value = {},
                bool enabled = true, qint64 timestamp = 0) {
    return {{"key", std::move(key)}, {"title", std::move(title)},
            {"subtitle", std::move(subtitle)}, {"state", std::move(state)},
            {"detail", std::move(detail)}, {"category", std::move(category)},
            {"value", std::move(value)}, {"enabled", enabled},
            {"timestamp", timestamp}};
}

bool executeAll(QSqlDatabase &database, const QStringList &statements, QString *error) {
    QSqlQuery query(database);
    for (const QString &statement : statements) {
        if (!query.exec(statement)) {
            if (error) *error = query.lastError().text();
            return false;
        }
    }
    return true;
}

} // namespace

QVariantMap ProviderResponsePolicy::evaluate(int status, const QString &contentType,
                                             const QByteArray &body,
                                             const QStringList &acceptedContentTypes,
                                             int maximumBytes, bool hasCache,
                                             const QString &networkError,
                                             const QByteArray &retryAfter) {
    QVariantMap result{{"state", hasCache ? "OFFLINE_CACHE" : "ERROR"},
                       {"acceptCache", false}, {"retryAfterSeconds", 0}};
    if (!networkError.isEmpty()) {
        result["detail"] = networkError.left(160);
        return result;
    }
    if (status == 304 && hasCache) {
        result["state"] = "CURRENT";
        result["detail"] = "Validated cached response";
        return result;
    }
    if (status == 429 || status == 503) {
        bool valid = false;
        const int seconds = QString::fromLatin1(retryAfter).toInt(&valid);
        result["retryAfterSeconds"] = valid ? qBound(0, seconds, 86400) : 0;
        result["detail"] = "Provider requested bounded retry delay";
        return result;
    }
    if (status < 200 || status >= 300) {
        result["detail"] = QStringLiteral("Provider HTTP status %1").arg(status);
        return result;
    }
    if (body.size() > maximumBytes) {
        result["detail"] = "Response exceeded provider bound";
        return result;
    }
    if (!acceptedContentTypes.contains(contentType)) {
        result["detail"] = "Unexpected provider content type";
        return result;
    }
    if (body.isEmpty()) {
        result["state"] = "EMPTY";
        result["detail"] = "Validated empty response";
        return result;
    }
    if (contentType == "application/json") {
        QJsonParseError parse;
        QJsonDocument::fromJson(body, &parse);
        if (parse.error != QJsonParseError::NoError) {
            result["detail"] = "Malformed JSON response";
            return result;
        }
    }
    result["state"] = "CURRENT";
    result["acceptCache"] = true;
    result["detail"] = QStringLiteral("%1 bytes · manual refresh").arg(body.size());
    return result;
}

MapListModel::MapListModel(QObject *parent) : QAbstractListModel(parent) {}

int MapListModel::rowCount(const QModelIndex &parent) const {
    return parent.isValid() ? 0 : m_rows.size();
}

QVariant MapListModel::data(const QModelIndex &index, int role) const {
    if (!index.isValid() || index.row() < 0 || index.row() >= m_rows.size()) return {};
    const QVariantMap value = m_rows.at(index.row()).toMap();
    switch (role) {
    case ItemRole: return value;
    case KeyRole: return value.value("key");
    case TitleRole: return value.value("title");
    case SubtitleRole: return value.value("subtitle");
    case StateRole: return value.value("state");
    case DetailRole: return value.value("detail");
    case CategoryRole: return value.value("category");
    case ValueRole: return value.value("value");
    case TimestampRole: return value.value("timestamp");
    case EnabledRole: return value.value("enabled");
    default: return {};
    }
}

QHash<int, QByteArray> MapListModel::roleNames() const {
    return {{ItemRole, "item"}, {KeyRole, "key"}, {TitleRole, "title"},
            {SubtitleRole, "subtitle"}, {StateRole, "state"}, {DetailRole, "detail"},
            {CategoryRole, "category"}, {ValueRole, "value"},
            {TimestampRole, "timestamp"}, {EnabledRole, "enabled"}};
}

QVariantMap MapListModel::item(int rowIndex) const {
    if (rowIndex < 0 || rowIndex >= m_rows.size()) return {};
    return m_rows.at(rowIndex).toMap();
}

void MapListModel::replace(QVariantList rows) {
    beginResetModel();
    m_rows = std::move(rows);
    endResetModel();
    emit countChanged();
}

void MapListModel::update(const QString &key, const QVariantMap &values) {
    for (int index = 0; index < m_rows.size(); ++index) {
        QVariantMap current = m_rows.at(index).toMap();
        if (current.value("key").toString() != key) continue;
        for (auto it = values.cbegin(); it != values.cend(); ++it) current.insert(it.key(), it.value());
        m_rows[index] = current;
        emit dataChanged(this->index(index), this->index(index));
        return;
    }
}

DesktopParityPlatform::DesktopParityPlatform(QObject *parent)
    : QObject(parent), m_homeModules(this), m_providers(this), m_digiModes(this),
      m_neuralOpportunities(this), m_contestDefinitions(this), m_contestLog(this),
      m_groupsMessages(this), m_portableActivity(this), m_satellitePasses(this),
      m_keyerMacros(this), m_closureLedger(this), m_nativeRadioProfiles(this),
      m_nativeRotatorProtocols(this), m_presets(this), m_eqBands(this),
      m_digiDecodes(this), m_bandMapRows(this), m_dxWorkspaceRows(this),
      m_intelligenceRows(this), m_operationsRows(this), m_alerts(this),
      m_groupsOutbox(this), m_groupsMemberships(this), m_groupsTopics(this),
      m_ownerHealth(this), m_network(this) {}

DesktopParityPlatform::~DesktopParityPlatform() { close(); }

bool DesktopParityPlatform::open(const QString &databaseDirectory, const QString &cacheDirectory,
                                 bool demo, QString *error) {
    close();
    if (!QDir().mkpath(databaseDirectory) || !QDir().mkpath(cacheDirectory)) {
        if (error) *error = "Cannot create desktop parity data directories";
        return false;
    }
    m_demoMode = demo;
    m_cacheDirectory = cacheDirectory;
    const QString token = QUuid::createUuid().toString(QUuid::WithoutBraces);
    m_stores = {
        {"Neural", databaseDirectory + "/neural-dx.sqlite", 5, "neural-" + token, {}},
        {"Digi", databaseDirectory + "/shackcq-digi.sqlite", 2, "digi-" + token, {}},
        {"Groups.io", databaseDirectory + "/shackcq-groupsio.sqlite", 2, "groups-" + token, {}},
        {"Contest", databaseDirectory + "/shackcq-contest.sqlite", 2, "contest-" + token, {}},
        {"DX Chaser", databaseDirectory + "/shackcq-dxchaser.sqlite", 1, "chaser-" + token, {}}
    };
    for (StoreSpec &store : m_stores) {
        if (!openStore(store, error)) {
            close();
            return false;
        }
    }
    loadRegistries();
    if (!loadFunctionalOwners(error)) {
        close();
        return false;
    }
    if (m_demoMode) seedDemo();
    m_closed = false;
    return true;
}

bool DesktopParityPlatform::openStore(StoreSpec &store, QString *error) {
    store.database = QSqlDatabase::addDatabase("QSQLITE", store.connection);
    store.database.setDatabaseName(store.path);
    if (!store.database.open()) {
        if (error) *error = QStringLiteral("%1 store: %2").arg(store.key, store.database.lastError().text());
        return false;
    }
    if (!executeAll(store.database, {"PRAGMA foreign_keys=ON", "PRAGMA journal_mode=WAL",
                                     "PRAGMA busy_timeout=5000"}, error)) return false;
    QSqlQuery versionQuery(store.database);
    if (!versionQuery.exec("PRAGMA user_version") || !versionQuery.next()) {
        if (error) *error = versionQuery.lastError().text();
        return false;
    }
    const int current = versionQuery.value(0).toInt();
    if (current > store.schema) {
        if (error) *error = QStringLiteral("%1 store schema %2 is newer than supported %3")
                                .arg(store.key).arg(current).arg(store.schema);
        return false;
    }
    return migrateStore(store, error);
}

bool DesktopParityPlatform::migrateStore(StoreSpec &store, QString *error) {
    if (!store.database.transaction()) {
        if (error) *error = store.database.lastError().text();
        return false;
    }
    QStringList statements;
    if (store.key == "Neural") {
        statements = {
            "CREATE TABLE IF NOT EXISTS evidence(id TEXT PRIMARY KEY, receiver_key TEXT NOT NULL, station_scope TEXT NOT NULL, slot_utc INTEGER NOT NULL, band TEXT NOT NULL, mode TEXT NOT NULL, callsign TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDING','HIT','MISS','UNVERIFIABLE')), sources_json TEXT NOT NULL, score REAL NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL, verified_at INTEGER)",
            "CREATE INDEX IF NOT EXISTS evidence_scope_slot ON evidence(station_scope,slot_utc,band)",
            "CREATE TABLE IF NOT EXISTS outlook(id TEXT PRIMARY KEY, station_scope TEXT NOT NULL, baseline_utc INTEGER NOT NULL, window_minutes INTEGER NOT NULL CHECK(window_minutes IN (30,60,120)), band TEXT NOT NULL, mode TEXT NOT NULL, support REAL NOT NULL, confidence REAL NOT NULL, reasons_json TEXT NOT NULL, candidates_json TEXT NOT NULL, calibration_state TEXT NOT NULL, created_at INTEGER NOT NULL)"
        };
    } else if (store.key == "Digi") {
        statements = {
            "CREATE TABLE IF NOT EXISTS decode(id TEXT PRIMARY KEY, mode TEXT NOT NULL, slot_utc INTEGER NOT NULL, callsign TEXT NOT NULL, message TEXT NOT NULL, snr INTEGER, source TEXT NOT NULL, created_at INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS decode_mode_slot ON decode(mode,slot_utc)",
            "CREATE TABLE IF NOT EXISTS tx_draft(id TEXT PRIMARY KEY, mode TEXT NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PREPARED','CANCELLED','COMPLETED')), operator_confirmed INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS sstv_gallery(id TEXT PRIMARY KEY, private_path TEXT NOT NULL, metadata_json TEXT NOT NULL, created_at INTEGER NOT NULL)"
        };
    } else if (store.key == "Groups.io") {
        statements = {
            "CREATE TABLE IF NOT EXISTS groups(id TEXT PRIMARY KEY, name TEXT NOT NULL, role TEXT NOT NULL, refreshed_at INTEGER)",
            "CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY, group_id TEXT NOT NULL, topic_id TEXT NOT NULL, subject TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, server_timestamp INTEGER NOT NULL, delivery_state TEXT NOT NULL, FOREIGN KEY(group_id) REFERENCES groups(id))",
            "CREATE INDEX IF NOT EXISTS messages_topic_time ON messages(group_id,topic_id,server_timestamp DESC)",
            "CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(message_id UNINDEXED, subject, body, tokenize='unicode61')",
            "CREATE TABLE IF NOT EXISTS drafts(id TEXT PRIMARY KEY, group_id TEXT NOT NULL, topic_id TEXT, subject TEXT NOT NULL, body TEXT NOT NULL, server_draft_id TEXT, updated_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY, draft_id TEXT NOT NULL, intent TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDING','AMBIGUOUS','DELIVERED','MODERATION')), attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER, created_at INTEGER NOT NULL)"
        };
    } else if (store.key == "Contest") {
        statements = {
            "CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, contest_id TEXT NOT NULL, rules_version INTEGER NOT NULL, station_profile_id TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('INACTIVE','ACTIVE','CLOSED','MERGED')), started_at INTEGER, ended_at INTEGER, settings_json TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS staging_qso(id TEXT PRIMARY KEY, session_id TEXT NOT NULL, callsign TEXT NOT NULL, band TEXT NOT NULL, mode TEXT NOT NULL, exchange_sent TEXT NOT NULL, exchange_received TEXT NOT NULL, points INTEGER NOT NULL, multiplier_key TEXT, created_at INTEGER NOT NULL, FOREIGN KEY(session_id) REFERENCES sessions(id))",
            "CREATE INDEX IF NOT EXISTS staging_session_time ON staging_qso(session_id,created_at)",
            "CREATE TABLE IF NOT EXISTS merge_ledger(staging_id TEXT PRIMARY KEY, canonical_qso_id TEXT NOT NULL, merged_at INTEGER NOT NULL)"
        };
    } else {
        statements = {
            "CREATE TABLE IF NOT EXISTS engagement(id TEXT PRIMARY KEY, callsign TEXT NOT NULL, band TEXT NOT NULL, mode TEXT NOT NULL, source TEXT NOT NULL, local_decode INTEGER NOT NULL, score REAL NOT NULL, state TEXT NOT NULL CHECK(state IN ('DRY_RUN','ELIGIBLE','LOCKED','COOLDOWN','CANCELLED')), started_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS engagement_target ON engagement(callsign,band,mode,updated_at DESC)"
        };
    }
    if (!executeAll(store.database, statements, error)) {
        store.database.rollback();
        return false;
    }
    QSqlQuery version(store.database);
    if (!version.exec(QStringLiteral("PRAGMA user_version=%1").arg(store.schema))) {
        if (error) *error = version.lastError().text();
        store.database.rollback();
        return false;
    }
    if (!store.database.commit()) {
        if (error) *error = store.database.lastError().text();
        return false;
    }
    return true;
}

void DesktopParityPlatform::loadRegistries() {
    const QStringList modules = {"Station / radio", "UTC / local clocks", "Weather", "Solar indices",
        "Space weather", "Aurora", "DX cluster", "DX News", "PSK / WSPR", "Band Health",
        "Neural opportunity", "Empirical Outlook", "Needs / watchlist", "Next satellite pass",
        "Portable activity", "Contest state", "Wavelog / logbook", "Groups.io", "System Health"};
    QVariantList home;
    for (int index = 0; index < modules.size(); ++index)
        home << row(QString::number(index), modules.at(index), "Configurable desktop module",
                    "AVAILABLE", {}, "Home", {}, true);
    m_homeModules.replace(home);

    const QList<ProviderSpec> providerList = {
        {"noaa-solar", "NOAA solar / space weather", QUrl("https://services.swpc.noaa.gov/products/summary/solar-wind-speed.json"), {"application/json"}, 262144, 300},
        {"nasa-sdo", "NASA SDO imagery", QUrl("https://sdo.gsfc.nasa.gov/assets/img/latest/latest_512_0171.jpg"), {"image/jpeg"}, 2097152, 900},
        {"ovation", "OVATION aurora", QUrl("https://services.swpc.noaa.gov/json/ovation_aurora_latest.json"), {"application/json"}, 4194304, 300},
        {"weather", "Weather", {}, {"application/json"}, 524288, 300},
        {"lightning", "Lightning", {}, {"application/json"}, 1048576, 300},
        {"dx-news", "DX News", {}, {"application/json", "application/xml"}, 1048576, 900},
        {"ng3k", "NG3K", {}, {"text/calendar", "application/json"}, 1048576, 900},
        {"contest-calendar", "Contest Calendar", {}, {"application/json"}, 1048576, 900},
        {"psk-reporter", "PSK Reporter", {}, {"application/json", "application/xml"}, 4194304, 300},
        {"personal-wspr", "Personal WSPR", {}, {"application/json"}, 2097152, 300},
        {"celestrak", "CelesTrak", {}, {"text/plain", "application/json"}, 2097152, 21600},
        {"satnogs", "SatNOGS", {}, {"application/json"}, 2097152, 900},
        {"amsat", "AMSAT", {}, {"application/json"}, 2097152, 900},
        {"pota", "POTA", {}, {"application/json"}, 2097152, 300},
        {"sota", "SOTA", {}, {"application/json", "text/plain"}, 2097152, 900},
        {"wwff", "WWFF Spotline", {}, {"application/json"}, 2097152, 300},
        {"iota", "IOTA", {}, {"application/json"}, 2097152, 900}
    };
    QVariantList providers;
    m_providerSpecs.clear();
    for (ProviderSpec spec : providerList) {
        spec.enabled = false;
        providers << row(spec.key, spec.title, spec.url.isEmpty() ? "Endpoint requires operator configuration" : spec.url.host(),
                         "UNAVAILABLE", "Disabled by default; no background polling", "Provider", {}, false);
        m_providerSpecs.insert(spec.key, spec);
    }
    m_providers.replace(providers);

    const QVariantList modes = {
        row("FT8", "FT8", "15 s slot", "AUTOMATIC_SEQUENCE", "Bounded CQ/S&P; TX acceptance required", "Digi"),
        row("FT4", "FT4", "7.5 s slot", "AUTOMATIC_SEQUENCE", "Bounded CQ/S&P; TX acceptance required", "Digi"),
        row("FT2", "FT2", "2 s slot", "MANUAL_RX_TX", "Experimental manual workflow", "Digi"),
        row("FST4", "FST4 variants", "15/30/60/120/300/900/1800 s", "MANUAL_RX_TX", {}, "Digi"),
        row("Q65", "Q65 variants", "A/B/C/D submodes", "MANUAL_RX_TX", {}, "Digi"),
        row("MSK144", "MSK144 variants", "Meteor scatter", "MANUAL_RX_TX", {}, "Digi"),
        row("JT65", "JT65 variants", "A/B/C", "MANUAL_RX_TX", {}, "Digi"),
        row("WSPR", "WSPR", "2 minute slots", "RECEIVE_ONLY", "No unattended beacon authority", "Digi"),
        row("CW", "CW", "Keyer authority", "MANUAL_ONLY", {}, "Digi"),
        row("RTTY", "RTTY", "AFSK", "MANUAL_RX_TX", {}, "Digi"),
        row("BPSK31", "BPSK31", "PSK", "MANUAL_RX_TX", {}, "Digi"),
        row("SSTV", "SSTV", "RX / prepared one-shot", "MANUAL_RX_TX", "Private gallery; explicit preview", "Digi")
    };
    m_digiModes.replace(modes);
    m_contestDefinitions.replace({
        row("CQ-WW", "CQ World Wide", "Built-in versioned rule definition", "READY", "Official rules review required; SCP refreshes at runtime", "Contest"),
        row("CQ-WPX", "CQ WPX", "Built-in versioned rule definition", "READY", "Official rules review required", "Contest"),
        row("ARRL-DX", "ARRL International DX", "Built-in versioned rule definition", "READY", "Official rules review required", "Contest"),
        row("IARU-HF", "IARU HF Championship", "Built-in versioned rule definition", "READY", "Official rules review required", "Contest")
    });
    m_keyerMacros.replace({
        row("F1", "F1 CQ", "CQ {MYCALL} {MYCALL}", "STOPPED", "Foreground shortcut; TX acceptance required", "Keyer"),
        row("F2", "F2 Exchange", "{HISCALL} 5NN {SERIAL}", "STOPPED", {}, "Keyer"),
        row("F3", "F3 Thanks", "TU {MYCALL}", "STOPPED", {}, "Keyer"),
        row("F4", "F4 My call", "{MYCALL}", "STOPPED", {}, "Keyer")
    });
    m_neuralOpportunities.replace({});
    m_contestLog.replace({});
    m_groupsMessages.replace({});
    m_portableActivity.replace({});
    m_satellitePasses.replace({});
}

void DesktopParityPlatform::seedDemo() {
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    m_neuralOpportunities.replace({
        row("demo-neural-1", "K1ABC · 20 m FT8", "Score 82 · evidence 0.78", "CURRENT", "RBN + PSK + personal WSPR; 30 min", "Neural", 82, true, now),
        row("demo-neural-2", "JA1XYZ · 15 m CW", "Score 71 · evidence 0.66", "CURRENT", "Cluster history + Band Health; 60 min", "Neural", 71, true, now - 120)
    });
    m_contestLog.replace({
        row("demo-qso-1", "DL1AAA", "20 m CW · 5NN 001", "STAGED", "2 points · multiplier DL", "Contest", 2, true, now - 90),
        row("demo-qso-2", "W1AW", "15 m CW · 5NN 002", "STAGED", "3 points · multiplier K", "Contest", 3, true, now - 35)
    });
    m_groupsMessages.replace({
        row("demo-message-1", "Field operations this weekend", "ShackCQ Field Group · Casey", "OFFLINE_CACHE", "3 replies · refreshed 4 min ago", "Groups.io", {}, true, now - 240),
        row("demo-message-2", "QMX portable audio routing", "Digital Operators · Morgan", "CURRENT", "7 replies · one attachment", "Groups.io", {}, true, now - 80)
    });
    m_portableActivity.replace({
        row("demo-pota", "K-1234 · K1ABC", "POTA · 14.062 MHz CW", "CURRENT", "Grid FN31 · 1,820 km", "Portable", 14062000, true, now - 55),
        row("demo-sota", "W1/AM-001 · N2XYZ", "SOTA · 7.032 MHz CW", "CURRENT", "Receive-only SOTA source", "Portable", 7032000, true, now - 110),
        row("demo-wwff", "DLFF-0123 · DL1AAA", "WWFF · 14.244 MHz SSB", "STALE", "Official Spotline cache", "Portable", 14244000, true, now - 900)
    });
    m_satellitePasses.replace({
        row("demo-pass-1", "ISS (ZARYA)", "AOS 12:41 UTC · max 48°", "LOCAL_SGP4", "Az 214° → 63° · 9 min", "Satellite", 48, true, now + 840),
        row("demo-pass-2", "SO-50", "AOS 13:18 UTC · max 27°", "LOCAL_SGP4", "Doppler receive preview available", "Satellite", 27, true, now + 3060),
        row("qo100", "QO-100", "Fixed pointing · az 286.4° · el 28.1°", "DESKTOP_EQUIVALENT", "Receive guidance; no automatic TX", "Satellite", 28.1, true, now)
    });
    for (auto it = m_providerSpecs.cbegin(); it != m_providerSpecs.cend(); ++it)
        m_providers.update(it.key(), {{"state", "OFFLINE_CACHE"}, {"detail", "Deterministic demo cache; network disabled"}});
}

QVariantMap DesktopParityPlatform::workspaceSummary(const QString &workspace) const {
    static const QHash<QString, QString> foundations{
        {"Home", "Home/HamClock"}, {"Digi", "Digi engines"},
        {"Contest", "Contest/N1MM"}, {"Groups.io", "Groups.io"},
        {"Portable", "Portable"}, {"Operations", "Operations planner"},
        {"Neural", "Neural DX/Empirical Outlook"}, {"Radio", "Native radio profiles"},
        {"Rotator", "Native rotator protocols"}, {"EQ", "EQ Studio"},
        {"Presets", "Presets/alerts/notifications"}, {"Band Maps", "Intelligent Band Maps"},
        {"DX", "DX workspace"}, {"Intelligence", "Intelligence/Awards/Contact Map"}};
    const QVariantMap closure = closureStatus(foundations.value(workspace, workspace));
    if (!closure.isEmpty())
        return {{"status", closure.value("state")},
                {"detail", closure.value("detail")}};
    return {{"status", "SOURCE_COMPLETE"},
            {"detail", "Existing single desktop authority"}};
}

QVariantMap DesktopParityPlatform::databaseHealth() const {
    QVariantMap result;
    for (const StoreSpec &store : m_stores) {
        result.insert(store.key, QVariantMap{{"open", store.database.isOpen()},
                     {"schema", store.schema}, {"file", QFileInfo(store.path).fileName()}});
    }
    return result;
}

QVariantMap DesktopParityPlatform::runDeterministicScaleProbe(QString *error) {
    QVariantMap result;
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    for (StoreSpec &store : m_stores) {
        QElapsedTimer timer;
        timer.start();
        if (!store.database.transaction()) {
            if (error) *error = store.database.lastError().text();
            return {};
        }
        QSqlQuery query(store.database);
        int expected = 0;
        if (store.key == "Neural") {
            expected = 180 * 16;
            if (!query.exec("DELETE FROM evidence") || !query.prepare("INSERT INTO evidence(id,receiver_key,station_scope,slot_utc,band,mode,callsign,state,sources_json,score,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) goto failed;
            for (int index = 0; index < expected; ++index) {
                query.bindValue(0, QStringLiteral("scale-neural-%1").arg(index));
                query.bindValue(1, "scale-receiver"); query.bindValue(2, "scale-station");
                query.bindValue(3, now - qint64(index) * 5400); query.bindValue(4, index % 2 ? "20m" : "40m");
                query.bindValue(5, index % 3 ? "FT8" : "CW"); query.bindValue(6, QStringLiteral("T%1AA").arg(index));
                query.bindValue(7, index % 4 ? "HIT" : "MISS"); query.bindValue(8, "[\"SCALE\"]");
                query.bindValue(9, double(index % 100)); query.bindValue(10, "deterministic scale evidence"); query.bindValue(11, now - index);
                if (!query.exec()) goto failed;
            }
        } else if (store.key == "Digi") {
            expected = 20000;
            if (!query.exec("DELETE FROM decode") || !query.prepare("INSERT INTO decode(id,mode,slot_utc,callsign,message,snr,source,created_at) VALUES(?,?,?,?,?,?,?,?)")) goto failed;
            for (int index = 0; index < expected; ++index) {
                query.bindValue(0, QStringLiteral("scale-digi-%1").arg(index)); query.bindValue(1, index % 2 ? "FT8" : "FT4");
                query.bindValue(2, now - index * 15); query.bindValue(3, QStringLiteral("T%1DX").arg(index));
                query.bindValue(4, "CQ SCALE TEST"); query.bindValue(5, -24 + index % 40); query.bindValue(6, "SCALE"); query.bindValue(7, now - index);
                if (!query.exec()) goto failed;
            }
        } else if (store.key == "Groups.io") {
            expected = 30000;
            if (!query.exec("DELETE FROM message_fts") || !query.exec("DELETE FROM messages") || !query.exec("DELETE FROM groups") ||
                !query.exec("INSERT INTO groups(id,name,role,refreshed_at) VALUES('scale-group','Scale Group','MEMBER',0)") ||
                !query.prepare("INSERT INTO messages(id,group_id,topic_id,subject,sender,body,server_timestamp,delivery_state) VALUES(?,?,?,?,?,?,?,?)")) goto failed;
            for (int index = 0; index < expected; ++index) {
                const QString id = QStringLiteral("scale-message-%1").arg(index);
                query.bindValue(0, id); query.bindValue(1, "scale-group"); query.bindValue(2, QStringLiteral("topic-%1").arg(index % 400));
                query.bindValue(3, QStringLiteral("Portable operations %1").arg(index % 100)); query.bindValue(4, "scale@example.invalid");
                query.bindValue(5, "Deterministic offline archive message body"); query.bindValue(6, now - index); query.bindValue(7, "DELIVERED");
                if (!query.exec()) goto failed;
                QSqlQuery fts(store.database); fts.prepare("INSERT INTO message_fts(message_id,subject,body) VALUES(?,?,?)");
                fts.addBindValue(id); fts.addBindValue(QStringLiteral("Portable operations %1").arg(index % 100)); fts.addBindValue("Deterministic offline archive message body");
                if (!fts.exec()) {
                    if (error) *error = QStringLiteral("Groups.io FTS scale probe: %1").arg(fts.lastError().text());
                    store.database.rollback();
                    return {};
                }
            }
        } else if (store.key == "Contest") {
            expected = 10000;
            if (!query.exec("DELETE FROM staging_qso") || !query.exec("DELETE FROM sessions") ||
                !query.exec("INSERT INTO sessions(id,contest_id,rules_version,station_profile_id,state,settings_json) VALUES('scale-session','SCALE',1,'scale-station','ACTIVE','{}')") ||
                !query.prepare("INSERT INTO staging_qso(id,session_id,callsign,band,mode,exchange_sent,exchange_received,points,multiplier_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) goto failed;
            for (int index = 0; index < expected; ++index) {
                query.bindValue(0, QStringLiteral("scale-contest-%1").arg(index)); query.bindValue(1, "scale-session");
                query.bindValue(2, QStringLiteral("T%1QSO").arg(index)); query.bindValue(3, index % 2 ? "20m" : "40m"); query.bindValue(4, "CW");
                query.bindValue(5, QStringLiteral("5NN %1").arg(index + 1)); query.bindValue(6, "5NN 001"); query.bindValue(7, 2); query.bindValue(8, QStringLiteral("M%1").arg(index % 300)); query.bindValue(9, now - index);
                if (!query.exec()) goto failed;
            }
        } else {
            expected = 20000;
            if (!query.exec("DELETE FROM engagement") || !query.prepare("INSERT INTO engagement(id,callsign,band,mode,source,local_decode,score,state,started_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) goto failed;
            for (int index = 0; index < expected; ++index) {
                query.bindValue(0, QStringLiteral("scale-chaser-%1").arg(index)); query.bindValue(1, QStringLiteral("T%1CHASE").arg(index));
                query.bindValue(2, "20m"); query.bindValue(3, "FT8"); query.bindValue(4, "LOCAL_DECODE"); query.bindValue(5, 1);
                query.bindValue(6, double(index % 100)); query.bindValue(7, "DRY_RUN"); query.bindValue(8, now - index); query.bindValue(9, now - index);
                if (!query.exec()) goto failed;
            }
        }
        if (!store.database.commit()) {
            if (error) *error = store.database.lastError().text();
            return {};
        }
        result.insert(store.key, QVariantMap{{"rows", expected}, {"insertMs", timer.elapsed()}});
        continue;
failed:
        if (error) *error = QStringLiteral("%1 scale probe: %2").arg(store.key, query.lastError().text());
        store.database.rollback();
        return {};
    }
    return result;
}

bool DesktopParityPlatform::setHomeModuleVisible(const QString &key, bool visible) {
    m_homeModules.update(key, {{"enabled", visible}});
    return true;
}

bool DesktopParityPlatform::setProviderEnabled(const QString &key, bool enabled) {
    auto it = m_providerSpecs.find(key);
    if (it == m_providerSpecs.end()) return false;
    it->enabled = enabled;
    m_providers.update(key, {{"enabled", enabled}, {"state", enabled ? "EMPTY" : "UNAVAILABLE"},
                             {"detail", enabled ? "Enabled; manual refresh only" : "Disabled by operator configuration"}});
    return true;
}

bool DesktopParityPlatform::refreshProvider(const QString &key) {
    auto it = m_providerSpecs.find(key);
    if (it == m_providerSpecs.end() || !it->enabled || it->url.isEmpty() || it->reply) return false;
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    if (it->lastAttempt > 0 && now - it->lastAttempt < it->cooldownSeconds) return false;
    it->lastAttempt = now;
    it->body.clear();
    QNetworkRequest request(it->url);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    request.setMaximumRedirectsAllowed(3);
    request.setTransferTimeout(15000);
    request.setRawHeader("Accept", it->contentTypes.join(',').toUtf8());
    const QString cachePath = m_cacheDirectory + "/provider-" + key + ".cache";
    if (QFileInfo::exists(cachePath))
        request.setRawHeader("If-Modified-Since", QFileInfo(cachePath).lastModified().toUTC().toString(Qt::RFC2822Date).toLatin1());
    it->reply = m_network.get(request);
    m_providers.update(key, {{"state", "CURRENT"}, {"detail", "Refresh in progress"}});
    connect(it->reply, &QNetworkReply::readyRead, this, [this, key] {
        auto provider = m_providerSpecs.find(key);
        if (provider == m_providerSpecs.end() || !provider->reply) return;
        provider->body += provider->reply->readAll();
        if (provider->body.size() > provider->maximumBytes) provider->reply->abort();
    });
    connect(it->reply, &QNetworkReply::finished, this, [this, key] { finishProvider(key); });
    return true;
}

void DesktopParityPlatform::finishProvider(const QString &key) {
    auto it = m_providerSpecs.find(key);
    if (it == m_providerSpecs.end() || !it->reply) return;
    QNetworkReply *reply = it->reply;
    it->body += reply->readAll();
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const QString contentType = reply->header(QNetworkRequest::ContentTypeHeader).toString().section(';', 0, 0).trimmed();
    const QString cachePath = m_cacheDirectory + "/provider-" + key + ".cache";
    const QString networkError = reply->error() == QNetworkReply::NoError ? QString{} : sanitizedNetworkError(reply->errorString());
    const QVariantMap decision = ProviderResponsePolicy::evaluate(
        status, contentType, it->body, it->contentTypes, it->maximumBytes,
        QFileInfo::exists(cachePath), networkError, reply->rawHeader("Retry-After"));
    if (decision.value("acceptCache").toBool()) {
        QSaveFile cache(cachePath);
        if (cache.open(QIODevice::WriteOnly) && cache.write(it->body) == it->body.size() && cache.commit())
            m_providers.update(key, decision);
        else
            m_providers.update(key, {{"state", "ERROR"}, {"detail", "Validated response could not be cached"}});
    } else {
        m_providers.update(key, decision);
        if (decision.value("state").toString() == "ERROR") emit providerError(key, decision.value("detail").toString());
    }
    reply->deleteLater();
    it->reply.clear();
    it->body.clear();
}

QString DesktopParityPlatform::sanitizedNetworkError(const QString &message) {
    QString clean = message;
    clean.remove(QRegularExpression(QStringLiteral("https?://\\S+")));
    return clean.left(160).trimmed().isEmpty() ? QStringLiteral("Provider request failed") : clean.left(160).trimmed();
}

bool DesktopParityPlatform::prepareReceiveReview(const QString &source, const QVariantMap &target) {
    const QString callsign = target.value("callsign", target.value("title")).toString().left(32);
    if (source.isEmpty() || callsign.isEmpty()) return false;
    setReview(QStringLiteral("Receive review · %1 · %2 · no CAT command sent").arg(source.left(40), callsign));
    return true;
}

bool DesktopParityPlatform::prepareContestMerge(const QVariantMap &session) {
    const QString id = session.value("id").toString();
    if (id.isEmpty()) return false;
    setReview(QStringLiteral("Contest merge review · %1 · canonical logging requires explicit confirmation").arg(id.left(64)));
    return true;
}

bool DesktopParityPlatform::prepareGroupsDraft(const QVariantMap &draft) {
    const QString subject = draft.value("subject").toString().trimmed();
    if (subject.isEmpty() || subject.size() > 200) return false;
    setReview(QStringLiteral("Groups.io draft review · %1 · nothing posted").arg(subject));
    return true;
}

bool DesktopParityPlatform::selectSatellitePass(const QVariantMap &pass) {
    const QString title = pass.value("title").toString();
    if (title.isEmpty()) return false;
    setReview(QStringLiteral("Satellite receive preview · %1 · no Doppler follow or TX").arg(title.left(80)));
    return true;
}

void DesktopParityPlatform::setReview(const QString &text) {
    if (m_activeReview == text) return;
    m_activeReview = text;
    emit activeReviewChanged();
}

void DesktopParityPlatform::clearReview() { setReview({}); }

void DesktopParityPlatform::setGalleryBandMapLayout(int value) {
    value = qBound(0, value, 3);
    if (m_galleryBandMapLayout == value) return;
    m_galleryBandMapLayout = value;
    emit galleryBandMapLayoutChanged();
}

void DesktopParityPlatform::globalStop() {
    for (auto it = m_providerSpecs.begin(); it != m_providerSpecs.end(); ++it) {
        if (it->reply) it->reply->abort();
    }
    if (m_groupsReply) m_groupsReply->abort();
    if (m_scpReply) m_scpReply->abort();
    clearReview();
    functionalStop();
    m_safetyState = "STOPPED / disconnected / TX off / automation disarmed";
    emit safetyStateChanged();
}

void DesktopParityPlatform::close() {
    if (m_closed && m_stores.isEmpty()) return;
    for (auto it = m_providerSpecs.begin(); it != m_providerSpecs.end(); ++it)
        if (it->reply) it->reply->abort();
    if (m_groupsReply) m_groupsReply->abort();
    if (m_scpReply) m_scpReply->abort();
    m_groupsReply.clear();
    m_scpReply.clear();
    stopN1mmRuntime();
    for (StoreSpec &store : m_stores) {
        const QString connection = store.connection;
        store.database.close();
        store.database = {};
        if (!connection.isEmpty()) QSqlDatabase::removeDatabase(connection);
    }
    m_stores.clear();
    m_closed = true;
}

} // namespace shackcq::desktop
