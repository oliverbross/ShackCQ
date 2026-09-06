#include "shackcq/desktop/DesktopParityPlatform.hpp"

#include "shackcq/satellite.h"
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
#include "shackcq_flex.h"
#endif

#include <QDateTime>
#include <QCryptographicHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QNetworkDatagram>
#include <QRegularExpression>
#include <QPointer>
#include <QSqlError>
#include <QSqlQuery>
#include <QTimeZone>
#include <QUrlQuery>
#include <QUuid>
#include <QXmlStreamReader>
#include <QtConcurrent>

#include <algorithm>
#include <array>
#include <cmath>

namespace shackcq::desktop {
namespace {

QVariantMap functionalRow(QString key, QString title, QString subtitle, QString state,
                          QString detail = {}, QString category = {}, QVariant value = {},
                          bool enabled = true, qint64 timestamp = 0) {
    return {{"key", std::move(key)}, {"title", std::move(title)},
            {"subtitle", std::move(subtitle)}, {"state", std::move(state)},
            {"detail", std::move(detail)}, {"category", std::move(category)},
            {"value", std::move(value)}, {"enabled", enabled}, {"timestamp", timestamp}};
}

bool runStatements(QSqlDatabase &database, const QStringList &statements, QString *error) {
    QSqlQuery query(database);
    for (const QString &statement : statements) {
        if (query.exec(statement)) continue;
        if (error) *error = query.lastError().text();
        return false;
    }
    return true;
}

QString normalizedCallsign(QString value) {
    value = value.trimmed().toUpper();
    static const QRegularExpression valid("^[A-Z0-9/]{3,16}$");
    return valid.match(value).hasMatch() ? value : QString{};
}

int digiModeId(const QString &mode) {
    static const QHash<QString, int> ids{{"FT8", 0}, {"FT4", 1}, {"FST4-15", 2},
        {"FST4-30", 3}, {"FST4-60", 4}, {"FST4-120", 5}, {"FST4-300", 6},
        {"Q65-30A", 7}, {"MSK144-15", 8}, {"JT65A", 9}, {"WSPR", 10},
        {"FT2", 11}, {"JT65B", 12}, {"JT65C", 13},
        {"CW", 100}, {"RTTY", 101}, {"PSK31", 102}, {"SSTV", 103}};
    return ids.value(mode.trimmed().toUpper(), -1);
}

} // namespace

DesktopParityPlatform::StoreSpec *DesktopParityPlatform::store(const QString &key) {
    for (StoreSpec &candidate : m_stores) if (candidate.key == key) return &candidate;
    return nullptr;
}

const DesktopParityPlatform::StoreSpec *DesktopParityPlatform::store(const QString &key) const {
    for (const StoreSpec &candidate : m_stores) if (candidate.key == key) return &candidate;
    return nullptr;
}

bool DesktopParityPlatform::loadFunctionalOwners(QString *error) {
    const QHash<QString, QStringList> additions{
        {"Neural", {
            "CREATE TABLE IF NOT EXISTS calibration(station_scope TEXT NOT NULL, band TEXT NOT NULL, window_minutes INTEGER NOT NULL, verified INTEGER NOT NULL, hits INTEGER NOT NULL, misses INTEGER NOT NULL, unverifiable INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(station_scope,band,window_minutes))"}},
        {"Digi", {
            "CREATE TABLE IF NOT EXISTS audio_route(id TEXT PRIMARY KEY, stable_device_id TEXT NOT NULL, sample_rate INTEGER NOT NULL, channel_count INTEGER NOT NULL, accepted INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS session(id TEXT PRIMARY KEY, mode TEXT NOT NULL, route_id TEXT NOT NULL, state TEXT NOT NULL, tx_accepted INTEGER NOT NULL DEFAULT 0, started_at INTEGER NOT NULL, stopped_at INTEGER)"}},
        {"Groups.io", {
            "CREATE TABLE IF NOT EXISTS memberships(group_id TEXT PRIMARY KEY, account_alias TEXT NOT NULL, role TEXT NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS topics(id TEXT PRIMARY KEY, group_id TEXT NOT NULL, subject TEXT NOT NULL, updated_at INTEGER NOT NULL, message_count INTEGER NOT NULL DEFAULT 0, closed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS sync_state(scope TEXT NOT NULL, scope_id TEXT NOT NULL, cursor TEXT, has_more INTEGER NOT NULL DEFAULT 0, last_success INTEGER, last_error TEXT, PRIMARY KEY(scope,scope_id))",
            "CREATE TABLE IF NOT EXISTS attachments(id TEXT PRIMARY KEY, message_id TEXT, draft_id TEXT, private_path TEXT NOT NULL, name TEXT NOT NULL, bytes INTEGER NOT NULL, state TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS delivery_ledger(outbox_id TEXT PRIMARY KEY, server_id TEXT, state TEXT NOT NULL, reconciled_at INTEGER NOT NULL)"}},
        {"Contest", {
            "CREATE TABLE IF NOT EXISTS serial_authority(session_id TEXT PRIMARY KEY, next_serial INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS n1mm_peer(id TEXT PRIMARY KEY, endpoint TEXT NOT NULL, trusted INTEGER NOT NULL DEFAULT 0, armed INTEGER NOT NULL DEFAULT 0, lifecycle TEXT NOT NULL DEFAULT 'DISCOVERED', last_seen INTEGER)",
            "CREATE TABLE IF NOT EXISTS n1mm_event(id TEXT PRIMARY KEY, peer_id TEXT NOT NULL, event_type TEXT NOT NULL, payload_digest TEXT NOT NULL, policy TEXT NOT NULL, received_at INTEGER NOT NULL, UNIQUE(peer_id,event_type,payload_digest))",
            "CREATE TABLE IF NOT EXISTS scp_manifest(id INTEGER PRIMARY KEY CHECK(id=1), source_url TEXT NOT NULL, source_date INTEGER NOT NULL, digest TEXT NOT NULL, row_count INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS scp_callsign(callsign TEXT PRIMARY KEY CHECK(length(callsign) BETWEEN 3 AND 16))",
            "CREATE TABLE IF NOT EXISTS scp_callsign_next(callsign TEXT PRIMARY KEY CHECK(length(callsign) BETWEEN 3 AND 16))"}},
        {"DX Chaser", {
            "CREATE TABLE IF NOT EXISTS attempt_journal(id TEXT PRIMARY KEY, engagement_id TEXT NOT NULL, event TEXT NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL)"}}
    };
    for (auto it = additions.cbegin(); it != additions.cend(); ++it) {
        StoreSpec *target = store(it.key());
        if (!target || !target->database.transaction()) {
            if (error) *error = QStringLiteral("%1 functional store unavailable").arg(it.key());
            return false;
        }
        if (!runStatements(target->database, it.value(), error) || !target->database.commit()) {
            target->database.rollback();
            return false;
        }
    }

    const QVariantList closure{
        functionalRow("Home/HamClock", "Home/HamClock", "19 live owner snapshots", "SOURCE_COMPLETE", "Provider last-good, empty and error truth", "Closure"),
        functionalRow("Neural DX/Empirical Outlook", "Neural DX/Empirical Outlook", "Schema 5 empirical pipeline", "SOURCE_COMPLETE", "56-day evidence and calibrated 30/60/120-minute windows", "Closure"),
        functionalRow("Native radio profiles", "Native radio profiles", "KX3/KX2, Flex, QMX/QMX+, RGO V6", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "Readback/capability first; TX acceptance false", "Closure"),
        functionalRow("Native rotator protocols", "Native rotator protocols", "Serial/TCP codecs under one rotator owner", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "Movement acceptance false", "Closure"),
        functionalRow("Digi engines", "Digi engines", "Linked native Rust modem and schema 2 sessions", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "RX/decode source complete; real TX locked", "Closure"),
        functionalRow("DX Chaser", "DX Chaser", "Local-decode eligibility and attempt journal", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "Cannot enable Digi TX", "Closure"),
        functionalRow("CW/Voice Keyer", "CW/Voice Keyer", "Typed macros, preview and bounded stopped queue", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "Send requires accepted radio/audio route", "Closure"),
        functionalRow("Intelligence/Awards/Contact Map", "Intelligence/Awards/Contact Map", "Paged QSO/RF projections and local estimates", "SOURCE_COMPLETE", "Licensed overlays remain licence-gated", "Closure"),
        functionalRow("Contest/N1MM", "Contest/N1MM", "Schema 2 staging, scoring and typed packets", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "N1MM disabled/loopback/untrusted/unarmed", "Closure"),
        functionalRow("Intelligent Band Maps", "Intelligent Band Maps", "One evaluator over shared spots", "SOURCE_COMPLETE", "No provider connection in Band Maps", "Closure"),
        functionalRow("DX workspace", "DX workspace", "Shared owner projections and explicit handoffs", "SOURCE_COMPLETE", "Evidence/news cannot command CAT", "Closure"),
        functionalRow("Portable", "Portable", "POTA/SOTA/WWFF/IOTA provider/cache adapters", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "Live providers independently pending", "Closure"),
        functionalRow("Operations planner", "Operations planner", "Cached calendars and spatial query scopes", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "Configured provider endpoints may be pending", "Closure"),
        functionalRow("Satellite/QO-100", "Satellite/QO-100", "Shared native SGP4 and receive guidance", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "No auto Doppler/TX/movement", "Closure"),
        functionalRow("Groups.io", "Groups.io", "Schema 2 archive, FTS, drafts and outbox", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "Authenticated API acceptance pending", "Closure"),
        functionalRow("Presets/alerts/notifications", "Presets/alerts/notifications", "Safe recall and native notification abstraction", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "Platform delivery acceptance pending", "Closure"),
        functionalRow("EQ Studio", "EQ Studio", "Elecraft draft/readback/verify transaction", "SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING", "Apply requires proven compatible readback", "Closure")
    };
    m_closureLedger.replace(closure);

    m_nativeRadioProfiles.replace({
        functionalRow("KX3", "Elecraft KX3", "Qt SerialPort · Elecraft CAT", "READY_READ_ONLY", "VFO/mode/filter/meters/gain/power/ATU/keyer/EQ capability probes", "Radio"),
        functionalRow("KX2", "Elecraft KX2", "Qt SerialPort · Elecraft CAT", "READY_READ_ONLY", "Model-specific capability snapshot", "Radio"),
        functionalRow("FLEX", "FlexRadio", "Native Rust SmartSDR LAN codec", "READY_READ_ONLY", "Discovery/slices/meters/panadapter; no SmartLink claim", "Radio"),
        functionalRow("QMX", "QMX", "CDC CAT + exact UAC identity", "READY_READ_ONLY", "No microphone fallback", "Radio"),
        functionalRow("QMX+", "QMX+", "CDC CAT + exact UAC identity", "READY_READ_ONLY", "Menu terminal and I/Q readiness", "Radio"),
        functionalRow("RGO-V6", "RGO ONE V6", "Reviewed V6 protocol", "READY_READ_ONLY", "Published capabilities only", "Radio"),
        functionalRow("RGO-UNKNOWN", "RGO conservative/unknown", "No unpublished framing assumptions", "READ_ONLY_UNKNOWN", "Legacy/TTL/filter/audio remain unknown", "Radio", {}, false)
    });
    m_nativeRotatorProtocols.replace({
        functionalRow("GS232", "GS-232A/B/generic", "serial or serial-over-TCP", "READY_DISARMED", "Query, bounds, Stop and confirmed move codec", "Rotator"),
        functionalRow("EASYCOMM", "EasyComm", "serial or serial-over-TCP", "READY_DISARMED", "Bidirectional headings and Stop", "Rotator"),
        functionalRow("ROTCTLD", "rotctld", "TCP", "READY_DISARMED", "Published rotctld command framing", "Rotator"),
        functionalRow("HAMLIB-DCU", "DCU/Rotor-EZ", "embedded pinned Hamlib", "READY_DISARMED", "Capability/readback governs setters", "Rotator"),
        functionalRow("HAMLIB-SPID", "SPID ROT1/ROT2", "embedded pinned Hamlib", "READY_DISARMED", "No guessed native binary frame", "Rotator"),
        functionalRow("ARCO", "ARCO compatibility modes", "published GS-232/EasyComm/rotctld only", "READY_DISARMED", "No proprietary microHAM protocol", "Rotator")
    });
    m_presets.replace({
        functionalRow("20m-cw", "20 m CW", "14.062 MHz · CW · 400 Hz", "READY", "Explicit capability review before recall", "Preset", 14062000),
        functionalRow("20m-ft8", "20 m FT8", "14.074 MHz · USB-D · 3 kHz", "READY", "Never connects or transmits", "Preset", 14074000),
        functionalRow("40m-field", "40 m Field", "7.032 MHz · CW · 500 Hz", "READY", "Never writes radio memory", "Preset", 7032000)
    });
    QVariantList bands;
    for (int index = 0; index < 8; ++index)
        bands << functionalRow(QString::number(index), QStringLiteral("Band %1").arg(index + 1),
                               "Radio readback unavailable", "PENDING_READBACK", "Draft 0 dB", "EQ", 0);
    m_eqBands.replace(bands);
    m_alerts.replace({
        functionalRow("DAY", "Day", "Normal theme and audible critical alerts", "READY", "Native delivery with in-app fallback", "Alert"),
        functionalRow("NIGHT", "Night", "Low-glare and quiet non-critical alerts", "READY", "Critical safety remains visible", "Alert"),
        functionalRow("FIELD", "Field", "High-contrast field policy", "READY", "Bounded tones and foreground banners", "Alert")
    });
    m_operationsRows.replace({
        functionalRow("dx-calendar", "DX Calendar", "last-good cache", "EMPTY", "Global/station/map-point/bounds queries", "Operations"),
        functionalRow("contest-calendar", "Contest Calendar", "versioned definitions", "READY", "Official rules links", "Operations"),
        functionalRow("activation-planner", "Activation Planner", "cached catalogues", "EMPTY", "No fetch on map pan", "Operations"),
        functionalRow("qo100", "QO-100", "fixed observer pointing", "READY_RX_ONLY", "No auto TX or rotator movement", "Satellite")
    });
    refreshOwnerHealth();
    return true;
}

QVariantMap DesktopParityPlatform::closureStatus(const QString &foundation) const {
    for (int index = 0; index < m_closureLedger.rowCount(); ++index) {
        const QVariantMap item = m_closureLedger.item(index);
        if (item.value("key").toString().compare(foundation, Qt::CaseInsensitive) == 0) return item;
    }
    return {};
}

QVariantMap DesktopParityPlatform::closureSummary() const {
    int sourceComplete = 0, foundationWired = 0, missing = 0, blocked = 0;
    for (int index = 0; index < m_closureLedger.rowCount(); ++index) {
        const QString state = m_closureLedger.item(index).value("state").toString();
        if (state.startsWith("SOURCE_COMPLETE")) ++sourceComplete;
        else if (state == "FOUNDATION_WIRED") ++foundationWired;
        else if (state == "MISSING") ++missing;
        else if (state.endsWith("_BLOCKED")) ++blocked;
    }
    return {{"audited", m_closureLedger.rowCount()},
            {"sourceComplete", sourceComplete},
            {"foundationWired", foundationWired},
            {"missing", missing},
            {"blocked", blocked},
            {"pass", m_closureLedger.rowCount() == 17 &&
                     sourceComplete == 17 && foundationWired == 0 && missing == 0}};
}

bool DesktopParityPlatform::updateOperatingContext(const QVariantMap &input) {
    QVariantMap next = m_operatingContext;
    static const QSet<QString> allowed{"station", "radioProfileId", "radioBackend", "radioModel",
        "receiver", "frequencyHz", "band", "mode", "audioRoute", "contestSession",
        "digiSession", "portableActivation", "rotatorProfile"};
    for (auto it = input.cbegin(); it != input.cend(); ++it) {
        if (!allowed.contains(it.key())) return false;
        next[it.key()] = it.value();
    }
    next["generation"] = m_operatingContext.value("generation").toULongLong() + 1;
    next["transmitAccepted"] = false;
    next["rotatorMovementAccepted"] = false;
    if (next == m_operatingContext) return true;
    m_operatingContext = next;
    emit operatingContextChanged();
    return true;
}

QVariantMap DesktopParityPlatform::nativeRadioFrame(const QString &profileId,
                                                     const QString &operation,
                                                     const QVariant &value) const {
    const QString profile = profileId.trimmed().toUpper();
    const QString op = operation.trimmed().toLower();
    QVariantMap result{{"profile", profile}, {"operation", op}, {"transmitAccepted", false},
                       {"requiresReadback", true}};
    if (profile == "KX3" || profile == "KX2" || profile == "QMX" || profile == "QMX+") {
        static const QHash<QString, QByteArray> queries{{"vfoa", "FA;"}, {"vfob", "FB;"},
            {"mode", "MD;"}, {"bandwidth", "BW;"}, {"split", "FT;"}, {"rit", "RT;"},
            {"xit", "XT;"}, {"power", "PC;"}, {"keyerspeed", "KS;"}};
        if (queries.contains(op)) {
            result["frame"] = queries.value(op);
            result["state"] = "QUERY";
            return result;
        }
        if (op == "setfrequency") {
            const quint64 hz = value.toULongLong();
            if (hz < 100000 || hz > 60000000) return {};
            result["frame"] = QStringLiteral("FA%1;").arg(hz, 11, 10, QLatin1Char('0')).toLatin1();
            result["state"] = "SAFE_SETTER_PENDING_ACCEPTANCE";
            return result;
        }
        result["state"] = "UNSUPPORTED_BY_PROVEN_CAPABILITY";
        return result;
    }
    if (profile == "FLEX") {
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
        std::array<char, 256> buffer{};
        int count = -1;
        if (op == "setfrequency") count = shackcq_flex_frequency(0, value.toULongLong(), buffer.data(), buffer.size());
        else if (op == "setmode") count = shackcq_flex_mode(0, value.toString().toUtf8().constData(), buffer.data(), buffer.size());
        else if (op == "keepalive") count = shackcq_flex_keepalive(buffer.data(), buffer.size());
        if (count >= 0) {
            result["frame"] = QByteArray(buffer.data(), count);
            result["state"] = "SAFE_SETTER_PENDING_ACCEPTANCE";
            return result;
        }
#endif
        result["state"] = "UNSUPPORTED_BY_PROVEN_CAPABILITY";
        return result;
    }
    result["state"] = profile == "RGO-V6" ? "PUBLISHED_V6_CAPABILITY_REQUIRED" : "UNKNOWN_REMAINS_UNKNOWN";
    return result;
}

QVariantMap DesktopParityPlatform::nativeRotatorFrame(const QString &protocol,
                                                       const QString &operation,
                                                       double azimuth,
                                                       double elevation) const {
    const QString id = protocol.trimmed().toUpper();
    const QString op = operation.trimmed().toLower();
    QVariantMap result{{"protocol", id}, {"operation", op}, {"movementAccepted", false}};
    const bool positionValid = azimuth >= 0 && azimuth <= 450 && elevation >= -10 && elevation <= 180;
    if (id == "GS232") {
        if (op == "query") result["frame"] = QByteArray("C2\r");
        else if (op == "stop") result["frame"] = QByteArray("S\r");
        else if (op == "move" && positionValid)
            result["frame"] = QStringLiteral("W%1 %2\r").arg(qRound(azimuth), 3, 10, QLatin1Char('0')).arg(qRound(elevation), 3, 10, QLatin1Char('0')).toLatin1();
        else return {};
    } else if (id == "EASYCOMM") {
        if (op == "query") result["frame"] = QByteArray("AZ EL\n");
        else if (op == "stop") result["frame"] = QByteArray("SA SE\n");
        else if (op == "move" && positionValid)
            result["frame"] = QStringLiteral("AZ%1 EL%2\n").arg(azimuth, 0, 'f', 1).arg(elevation, 0, 'f', 1).toLatin1();
        else return {};
    } else if (id == "ROTCTLD") {
        if (op == "query") result["frame"] = QByteArray("p\n");
        else if (op == "stop") result["frame"] = QByteArray("S\n");
        else if (op == "move" && positionValid)
            result["frame"] = QStringLiteral("P %1 %2\n").arg(azimuth, 0, 'f', 1).arg(elevation, 0, 'f', 1).toLatin1();
        else return {};
    } else if (id.startsWith("HAMLIB-") || id == "ARCO") {
        result["state"] = "EMBEDDED_HAMLIB_CAPABILITY_READBACK";
        return result;
    } else return {};
    result["state"] = op == "move" ? "CONFIRMATION_AND_ACCEPTANCE_REQUIRED" : "READY";
    return result;
}

bool DesktopParityPlatform::savePreset(const QVariantMap &input) {
    const QString id = input.value("id").toString().trimmed().left(64);
    const QString title = input.value("title").toString().trimmed().left(80);
    const quint64 frequency = input.value("frequencyHz").toULongLong();
    if (id.isEmpty() || title.isEmpty() || frequency < 100000 || frequency > 10500000000ULL) return false;
    m_presets.update(id, {{"title", title}, {"subtitle", input.value("detail")}, {"value", frequency}, {"state", "READY"}});
    bool exists = false;
    for (int index = 0; index < m_presets.rowCount(); ++index) if (m_presets.item(index).value("key") == id) exists = true;
    if (!exists) {
        QVariantList rows;
        for (int index = 0; index < m_presets.rowCount(); ++index) rows << m_presets.item(index);
        rows << functionalRow(id, title, input.value("detail").toString(), "READY", "Explicit capability review before recall", "Preset", frequency);
        m_presets.replace(rows);
    }
    return true;
}

bool DesktopParityPlatform::removePreset(const QString &presetId) {
    QVariantList rows;
    bool removed = false;
    for (int index = 0; index < m_presets.rowCount(); ++index) {
        const QVariantMap item = m_presets.item(index);
        if (item.value("key").toString() == presetId) removed = true; else rows << item;
    }
    if (removed) m_presets.replace(rows);
    return removed;
}

bool DesktopParityPlatform::reviewPresetRecall(const QString &presetId) {
    for (int index = 0; index < m_presets.rowCount(); ++index) {
        const QVariantMap item = m_presets.item(index);
        if (item.value("key").toString() != presetId) continue;
        setReview(QStringLiteral("Preset recall review · %1 · explicit receive-safe setters only; no connect/PTT/TUNE/memory write").arg(item.value("title").toString()));
        return true;
    }
    return false;
}

bool DesktopParityPlatform::saveEqDraft(const QVariantList &rxBands, const QVariantList &txBands) {
    if (rxBands.size() != 8 || txBands.size() != 8) return false;
    auto valid = [](const QVariantList &values) {
        return std::all_of(values.cbegin(), values.cend(), [](const QVariant &value) {
            bool ok = false; const int gain = value.toInt(&ok); return ok && gain >= -16 && gain <= 16;
        });
    };
    if (!valid(rxBands) || !valid(txBands)) return false;
    m_eqRxDraft = rxBands; m_eqTxDraft = txBands;
    for (int index = 0; index < 8; ++index)
        m_eqBands.update(QString::number(index), {{"state", "LOCAL_DRAFT"},
            {"subtitle", "Radio readback pending"},
            {"detail", QStringLiteral("RX %1 dB · TX %2 dB").arg(rxBands.at(index).toInt()).arg(txBands.at(index).toInt())}});
    return true;
}

bool DesktopParityPlatform::reviewEqApply() {
    if (m_eqRxDraft.size() != 8 || m_eqTxDraft.size() != 8) return false;
    setReview("EQ apply review · requires proven compatible Elecraft profile, exclusive CAT transaction, readback verification and rollback; no command sent");
    return true;
}

bool DesktopParityPlatform::startDigiReceive(const QString &mode, const QString &audioRouteId,
                                              int sampleRate) {
    if (digiModeId(mode) < 0 || audioRouteId.trimmed().isEmpty() || sampleRate < 8000 || sampleRate > 192000) return false;
    stopDigi();
    StoreSpec *digi = store("Digi");
    if (!digi) return false;
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    QSqlQuery query(digi->database);
    query.prepare("INSERT INTO session(id,mode,route_id,state,tx_accepted,started_at) VALUES(?,?,?,?,0,?)");
    query.addBindValue(id); query.addBindValue(mode.toUpper()); query.addBindValue(audioRouteId.trimmed());
    query.addBindValue("RX_RUNNING"); query.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!query.exec()) return false;
    ++m_digiGeneration;
    m_digiAudioBuffer.clear();
    m_digiAudioSampleRate = quint32(sampleRate);
    m_activeDigiMode = mode.toUpper(); m_activeAudioRoute = audioRouteId.trimmed();
    m_digiState = QStringLiteral("RX_RUNNING / %1 / %2 Hz / TX locked").arg(m_activeDigiMode).arg(sampleRate);
    emit workflowStateChanged();
    return true;
}

void DesktopParityPlatform::stopDigi() {
    ++m_digiGeneration;
    if (StoreSpec *digi = store("Digi")) {
        QSqlQuery query(digi->database);
        query.prepare("UPDATE session SET state='STOPPED',stopped_at=? WHERE state='RX_RUNNING'");
        query.addBindValue(QDateTime::currentSecsSinceEpoch()); query.exec();
    }
    m_activeDigiMode.clear(); m_activeAudioRoute.clear();
    m_digiAudioBuffer.clear(); m_digiAudioSampleRate = 0;
    m_digiState = "STOPPED / no audio route / TX locked";
    emit workflowStateChanged();
}

QVariantList DesktopParityPlatform::decodeDigiSlotForTest(const QString &mode,
                                                          const QVector<float> &samples,
                                                          quint32 sampleRate,
                                                          QString *error) const {
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
    const int id = digiModeId(mode);
    if (id < 0 || samples.isEmpty() || sampleRate == 0) {
        if (error) *error = "Invalid native Digi decode request";
        return {};
    }
    QByteArray output(1024 * 1024, '\0');
    int count = -1;
    if (id < 100) {
        count = shackcq_digi_decode_slot(id, samples.constData(), size_t(samples.size()), sampleRate,
                                    output.data(), size_t(output.size()));
    } else if (id == 102) {
        count = shackcq_digi_decode_psk31(samples.constData(), size_t(samples.size()), 1000.0f,
                                     output.data(), size_t(output.size()));
    } else {
        shackcq_digi_context *context = shackcq_digi_context_create(sampleRate, 700.0f, false, 2125.0f);
        if (!context) {
            if (error) *error = "Cannot allocate native streaming Digi context";
            return {};
        }
        if (id == 100)
            count = shackcq_digi_feed_cw(context, samples.constData(), size_t(samples.size()),
                                    output.data(), size_t(output.size()));
        else if (id == 101)
            count = shackcq_digi_feed_rtty(context, samples.constData(), size_t(samples.size()),
                                      output.data(), size_t(output.size()));
        else
            count = shackcq_digi_feed_sstv(context, samples.constData(), size_t(samples.size()),
                                      output.data(), size_t(output.size()));
        shackcq_digi_context_destroy(context);
    }
    if (count < 0) {
        if (error) *error = "Native Digi engine rejected the slot";
        return {};
    }
    QJsonParseError parse;
    const QJsonDocument document = QJsonDocument::fromJson(output.left(count), &parse);
    if (parse.error != QJsonParseError::NoError) {
        if (error) *error = "Native Digi engine returned malformed JSON";
        return {};
    }
    if (document.isArray()) return document.array().toVariantList();
    if (document.isObject()) return {document.object().toVariantMap()};
    return {};
#else
    Q_UNUSED(mode); Q_UNUSED(samples); Q_UNUSED(sampleRate);
    if (error) *error = "Native Digi engine not linked";
    return {};
#endif
}

void DesktopParityPlatform::feedDigiAudio(const QString &audioRouteId,
                                          quint32 sampleRate,
                                          const QVector<float> &samples) {
    if (!m_digiState.startsWith("RX_RUNNING") || samples.isEmpty() ||
        audioRouteId != m_activeAudioRoute || sampleRate != m_digiAudioSampleRate ||
        samples.size() > 2'000'000)
        return;
    static const QHash<QString, double> seconds{{"FT8", 15}, {"FT4", 7.5},
        {"FST4-15", 15}, {"FST4-30", 30}, {"FST4-60", 60},
        {"FST4-120", 120}, {"FST4-300", 300}, {"Q65-30A", 30},
        {"MSK144-15", 15}, {"JT65A", 60}, {"JT65B", 60},
        {"JT65C", 60}, {"WSPR", 120}, {"FT2", 2},
        {"CW", 1}, {"RTTY", 1}, {"PSK31", 1}, {"SSTV", 1}};
    const qint64 required = qint64(seconds.value(m_activeDigiMode, 0) * sampleRate);
    if (required <= 0 || required > 16'000'000)
        return;
    if (m_digiAudioBuffer.size() + samples.size() > 16'000'000) {
        stopDigi();
        setReview("Digi receive stopped: bounded audio buffer exceeded");
        return;
    }
    m_digiAudioBuffer += samples;
    if (m_digiAudioBuffer.size() < required)
        return;
    QVector<float> slot = m_digiAudioBuffer.mid(0, required);
    m_digiAudioBuffer.remove(0, required);
    const QString mode = m_activeDigiMode;
    const quint64 generation = m_digiGeneration.load();
    QPointer<DesktopParityPlatform> self(this);
    (void)QtConcurrent::run([self, slot = std::move(slot), mode, sampleRate, generation] {
        if (!self) return;
        QString error;
        const QVariantList decoded =
            self->decodeDigiSlotForTest(mode, slot, sampleRate, &error);
        QMetaObject::invokeMethod(self, [self, decoded, mode, generation, error] {
            if (!self || generation != self->m_digiGeneration.load()) return;
            if (!error.isEmpty()) {
                self->setReview(QStringLiteral("Digi decode error: %1").arg(error.left(180)));
                return;
            }
            const qint64 now = QDateTime::currentSecsSinceEpoch();
            for (const QVariant &entry : decoded) {
                QVariantMap value = entry.toMap();
                value["id"] = value.value("id", QUuid::createUuid().toString(QUuid::WithoutBraces));
                value["mode"] = mode;
                value["slotUtc"] = value.value("slotUtc", now);
                value["source"] = "LIVE_CAPTURE";
                self->ingestLocalDecode(value);
            }
        }, Qt::QueuedConnection);
    });
}

bool DesktopParityPlatform::ingestLocalDecode(const QVariantMap &input) {
    const QString callsign = normalizedCallsign(input.value("callsign").toString());
    const QString mode = input.value("mode", m_activeDigiMode).toString().toUpper();
    const qint64 slot = input.value("slotUtc").toLongLong();
    if (callsign.isEmpty() || digiModeId(mode) < 0 || slot <= 0 || input.value("source").toString() != "LIVE_CAPTURE") return false;
    StoreSpec *digi = store("Digi");
    if (!digi) return false;
    const QString id = input.value("id", QUuid::createUuid().toString(QUuid::WithoutBraces)).toString();
    QSqlQuery query(digi->database);
    query.prepare("INSERT OR REPLACE INTO decode(id,mode,slot_utc,callsign,message,snr,source,created_at) VALUES(?,?,?,?,?,?,?,?)");
    query.addBindValue(id); query.addBindValue(mode); query.addBindValue(slot); query.addBindValue(callsign);
    query.addBindValue(input.value("message").toString().left(256)); query.addBindValue(input.value("snr").toInt());
    query.addBindValue("LIVE_CAPTURE"); query.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!query.exec()) return false;
    QVariantList rows;
    for (int index = 0; index < m_digiDecodes.rowCount(); ++index) rows << m_digiDecodes.item(index);
    rows.prepend(functionalRow(id, callsign, QStringLiteral("%1 · %2 dB").arg(mode).arg(input.value("snr").toInt()),
                               "LIVE_CAPTURE", input.value("message").toString().left(256), "Digi", {}, true, slot));
    while (rows.size() > 500) rows.removeLast();
    m_digiDecodes.replace(rows);
    return true;
}

bool DesktopParityPlatform::startDxChaser(const QVariantMap &candidate, bool dryRun) {
    const QString callsign = normalizedCallsign(candidate.value("callsign").toString());
    const QString source = candidate.value("source").toString();
    const QString band = candidate.value("band").toString().trimmed();
    const QString mode = candidate.value("mode").toString().trimmed().toUpper();
    const qint64 slot = candidate.value("slotUtc").toLongLong();
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    if (callsign.isEmpty() || band.isEmpty() || mode.isEmpty() ||
        (source != "LIVE_CAPTURE" && source != "REDECODE_LIVE_SLOT") || std::abs(now - slot) > 30) {
        setReview("DX Chaser rejected candidate: requires a fresh LIVE_CAPTURE or REDECODE_LIVE_SLOT callsign");
        return false;
    }
    StoreSpec *chaser = store("DX Chaser");
    if (!chaser) return false;
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    double score = 25.0 + (candidate.value("needed").toBool() ? 35.0 : 0.0) +
                   (candidate.value("watchlisted").toBool() ? 25.0 : 0.0) +
                   std::clamp(candidate.value("evidence").toDouble(), 0.0, 1.0) * 15.0;
    QSqlQuery query(chaser->database);
    query.prepare("INSERT INTO engagement(id,callsign,band,mode,source,local_decode,score,state,started_at,updated_at) VALUES(?,?,?,?,?,1,?,?,?,?)");
    query.addBindValue(id); query.addBindValue(callsign); query.addBindValue(band);
    query.addBindValue(mode); query.addBindValue(source); query.addBindValue(score);
    query.addBindValue(dryRun ? "DRY_RUN" : "ELIGIBLE"); query.addBindValue(now); query.addBindValue(now);
    if (!query.exec()) {
        setReview(QStringLiteral("DX Chaser journal error: %1").arg(query.lastError().text().left(180)));
        return false;
    }
    setReview(QStringLiteral("DX Chaser %1 · %2 · score %3 · local decode eligible · TX remains locked")
                  .arg(dryRun ? "Dry Run" : "Assist", callsign).arg(qRound(score)));
    return true;
}

bool DesktopParityPlatform::startContest(const QString &definitionId, const QString &stationProfileId) {
    if (definitionId.trimmed().isEmpty() || stationProfileId.trimmed().isEmpty() || !m_activeContestSession.isEmpty()) return false;
    StoreSpec *contest = store("Contest");
    if (!contest) return false;
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    QSqlQuery query(contest->database);
    query.prepare("INSERT INTO sessions(id,contest_id,rules_version,station_profile_id,state,started_at,settings_json) VALUES(?,?,1,?,'ACTIVE',?,'{}')");
    query.addBindValue(id); query.addBindValue(definitionId.trimmed()); query.addBindValue(stationProfileId.trimmed());
    query.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!query.exec()) return false;
    QSqlQuery serial(contest->database);
    serial.prepare("INSERT INTO serial_authority(session_id,next_serial) VALUES(?,1)"); serial.addBindValue(id);
    if (!serial.exec()) return false;
    m_activeContestSession = id; m_contestState = QStringLiteral("ACTIVE / %1 / N1MM unarmed").arg(definitionId);
    emit workflowStateChanged();
    return true;
}

bool DesktopParityPlatform::stageContestQso(const QVariantMap &input) {
    if (m_activeContestSession.isEmpty()) return false;
    const QString callsign = normalizedCallsign(input.value("callsign").toString());
    const QString band = input.value("band").toString(); const QString mode = input.value("mode").toString();
    if (callsign.isEmpty() || band.isEmpty() || mode.isEmpty()) return false;
    StoreSpec *contest = store("Contest"); if (!contest) return false;
    QSqlQuery dupe(contest->database);
    dupe.prepare("SELECT 1 FROM staging_qso WHERE session_id=? AND callsign=? AND band=? AND mode=? LIMIT 1");
    dupe.addBindValue(m_activeContestSession); dupe.addBindValue(callsign); dupe.addBindValue(band); dupe.addBindValue(mode);
    if (!dupe.exec() || dupe.next()) return false;
    QSqlQuery serial(contest->database);
    serial.prepare("SELECT next_serial FROM serial_authority WHERE session_id=?"); serial.addBindValue(m_activeContestSession);
    if (!serial.exec() || !serial.next()) return false;
    const int nextSerial = serial.value(0).toInt();
    const int points = std::clamp(input.value("points", 1).toInt(), 0, 100);
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    QSqlQuery insert(contest->database);
    insert.prepare("INSERT INTO staging_qso(id,session_id,callsign,band,mode,exchange_sent,exchange_received,points,multiplier_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)");
    insert.addBindValue(id); insert.addBindValue(m_activeContestSession); insert.addBindValue(callsign);
    insert.addBindValue(band); insert.addBindValue(mode);
    insert.addBindValue(input.value("exchangeSent", QStringLiteral("5NN %1").arg(nextSerial, 3, 10, QLatin1Char('0'))));
    insert.addBindValue(input.value("exchangeReceived").toString().left(64)); insert.addBindValue(points);
    insert.addBindValue(input.value("multiplier").toString().left(32)); insert.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!insert.exec()) return false;
    QSqlQuery update(contest->database);
    update.prepare("UPDATE serial_authority SET next_serial=next_serial+1 WHERE session_id=?"); update.addBindValue(m_activeContestSession); update.exec();
    QVariantList rows;
    for (int index = 0; index < m_contestLog.rowCount(); ++index) rows << m_contestLog.item(index);
    rows.prepend(functionalRow(id, callsign, QStringLiteral("%1 %2 · %3").arg(band, mode, insert.boundValue(5).toString()),
                               "STAGED", QStringLiteral("%1 points").arg(points), "Contest", points, true, QDateTime::currentSecsSinceEpoch()));
    m_contestLog.replace(rows);
    return true;
}

QVariantMap DesktopParityPlatform::contestScore() const {
    const StoreSpec *contest = store("Contest");
    if (!contest || m_activeContestSession.isEmpty()) return {{"qsos", 0}, {"points", 0}, {"multipliers", 0}, {"score", 0}};
    QSqlQuery query(contest->database);
    query.prepare("SELECT COUNT(*),COALESCE(SUM(points),0),COUNT(DISTINCT NULLIF(multiplier_key,'')) FROM staging_qso WHERE session_id=?");
    query.addBindValue(m_activeContestSession);
    if (!query.exec() || !query.next()) return {};
    const int qsos = query.value(0).toInt(), points = query.value(1).toInt(), multipliers = query.value(2).toInt();
    return {{"qsos", qsos}, {"points", points}, {"multipliers", multipliers}, {"score", points * std::max(1, multipliers)}};
}

QVariantMap DesktopParityPlatform::parseN1mmPacket(const QByteArray &packet) const {
    if (packet.isEmpty() || packet.size() > 65536 || packet.contains("<!DOCTYPE") || packet.contains("<!ENTITY")) return {};
    QXmlStreamReader xml(packet);
    QVariantMap result;
    static const QSet<QString> packetTypes{"appinfo", "contactinfo", "contactreplace",
        "contactdelete", "lookupinfo", "radioinfo", "spot", "scoreinfo", "talk", "dynamicresults"};
    static const QSet<QString> fields{"app", "timestamp", "mycall", "band", "call", "contestnr",
        "stationname", "id", "operator", "rxfreq", "txfreq", "mode", "contestname", "contestid",
        "category", "assisted", "isoriginal", "wpxprefix", "continent", "snt", "sntnr", "rcv",
        "rcvnr", "gridsquare", "section", "prec", "ck", "zone", "power", "points", "radionr",
        "runnr", "isrunqso", "stationprefix", "wpxprefix2", "exchange1", "contacttype", "networkedcomputer",
        "ismultiplier1", "isofftimeqso", "ismultiplier2", "frequency", "comment"};
    if (!xml.readNextStartElement()) return {};
    const QString type = xml.name().toString().toLower();
    if (!packetTypes.contains(type)) return {};
    result["type"] = type;
    int count = 0;
    while (xml.readNextStartElement()) {
        const QString name = xml.name().toString().toLower();
        const QString value = xml.readElementText(QXmlStreamReader::SkipChildElements).left(512);
        if (!fields.contains(name) || ++count > 43) return {};
        result[name] = value;
    }
    if (xml.hasError()) return {};
    result["trusted"] = false; result["armed"] = false; result["fieldCount"] = count;
    result["policy"] = "REVIEW_ONLY";
    result["dedupeKey"] = QString::fromLatin1(QCryptographicHash::hash(packet, QCryptographicHash::Sha256).toHex());
    return result;
}

bool DesktopParityPlatform::registerN1mmPeer(const QString &peerId, const QString &endpoint) {
    const QString id = peerId.trimmed().left(64);
    const QUrl url = QUrl::fromUserInput(endpoint.trimmed());
    if (id.isEmpty() || !url.isValid() || url.host().isEmpty() || url.port() < 1 || url.port() > 65535) return false;
    StoreSpec *contest = store("Contest"); if (!contest) return false;
    QSqlQuery query(contest->database);
    query.prepare("INSERT INTO n1mm_peer(id,endpoint,trusted,armed,lifecycle,last_seen) VALUES(?,?,0,0,'DISCOVERED',?) ON CONFLICT(id) DO UPDATE SET endpoint=excluded.endpoint,armed=0,lifecycle='DISCOVERED',last_seen=excluded.last_seen");
    query.addBindValue(id); query.addBindValue(url.toString()); query.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!query.exec()) return false;
    m_n1mmState = QStringLiteral("DISCOVERED / %1 / untrusted / unarmed").arg(id);
    emit workflowStateChanged();
    return true;
}

bool DesktopParityPlatform::setN1mmPeerTrusted(const QString &peerId, bool trusted) {
    StoreSpec *contest = store("Contest"); if (!contest) return false;
    QSqlQuery query(contest->database);
    query.prepare("UPDATE n1mm_peer SET trusted=?,armed=0,last_seen=? WHERE id=?");
    query.addBindValue(trusted ? 1 : 0); query.addBindValue(QDateTime::currentSecsSinceEpoch()); query.addBindValue(peerId.trimmed());
    if (!query.exec() || query.numRowsAffected() != 1) return false;
    m_n1mmState = QStringLiteral("PAIRED / %1 / %2 / unarmed").arg(peerId.trimmed(), trusted ? "trusted" : "untrusted");
    emit workflowStateChanged();
    return true;
}

bool DesktopParityPlatform::updateN1mmPeerLifecycle(const QString &peerId, const QString &event) {
    static const QSet<QString> allowed{"DISCOVERED", "CONNECTED", "HEARTBEAT", "DISCONNECTED"};
    const QString state = event.trimmed().toUpper();
    if (!allowed.contains(state)) return false;
    StoreSpec *contest = store("Contest"); if (!contest) return false;
    QSqlQuery query(contest->database);
    query.prepare("UPDATE n1mm_peer SET lifecycle=?,armed=0,last_seen=? WHERE id=?");
    query.addBindValue(state); query.addBindValue(QDateTime::currentSecsSinceEpoch()); query.addBindValue(peerId.trimmed());
    if (!query.exec() || query.numRowsAffected() != 1) return false;
    m_n1mmState = QStringLiteral("%1 / %2 / unarmed").arg(state, peerId.trimmed());
    emit workflowStateChanged();
    return true;
}

bool DesktopParityPlatform::startN1mmDiscovery(quint16 port) {
    if (m_n1mmUdp.state() != QAbstractSocket::UnconnectedState ||
        !m_n1mmUdp.bind(QHostAddress::LocalHost, port,
                        QUdpSocket::ShareAddress | QUdpSocket::ReuseAddressHint)) return false;
    disconnect(&m_n1mmUdp, nullptr, this, nullptr);
    connect(&m_n1mmUdp, &QUdpSocket::readyRead, this, [this] {
        while (m_n1mmUdp.hasPendingDatagrams()) {
            const QNetworkDatagram datagram = m_n1mmUdp.receiveDatagram(65536);
            if (!datagram.isValid() || datagram.data().isEmpty()) continue;
            const QString endpoint = QStringLiteral("udp://%1:%2").arg(datagram.senderAddress().toString()).arg(datagram.senderPort());
            const QString peerId = QStringLiteral("udp-%1").arg(QString::fromLatin1(
                QCryptographicHash::hash(endpoint.toUtf8(), QCryptographicHash::Sha256).toHex().left(16)));
            if (registerN1mmPeer(peerId, endpoint)) ingestN1mmPacket(peerId, datagram.data());
        }
    });
    m_n1mmState = QStringLiteral("LISTENING / 127.0.0.1:%1 / untrusted / unarmed").arg(m_n1mmUdp.localPort());
    emit workflowStateChanged();
    return true;
}

bool DesktopParityPlatform::connectN1mmPeer(const QString &peerId) {
    if (m_n1mmTcp.state() != QAbstractSocket::UnconnectedState) return false;
    StoreSpec *contest = store("Contest"); if (!contest) return false;
    QSqlQuery query(contest->database);
    query.prepare("SELECT endpoint,trusted FROM n1mm_peer WHERE id=?"); query.addBindValue(peerId.trimmed());
    if (!query.exec() || !query.next() || !query.value(1).toBool()) return false;
    const QUrl endpoint(query.value(0).toString());
    if (endpoint.scheme() != "tcp" || endpoint.host().isEmpty() || endpoint.port() < 1) return false;
    m_n1mmTcpPeerId = peerId.trimmed(); m_n1mmTcpBuffer.clear();
    disconnect(&m_n1mmTcp, nullptr, this, nullptr);
    connect(&m_n1mmTcp, &QTcpSocket::connected, this, [this] {
        updateN1mmPeerLifecycle(m_n1mmTcpPeerId, "CONNECTED");
    });
    connect(&m_n1mmTcp, &QTcpSocket::readyRead, this, [this] {
        m_n1mmTcpBuffer += m_n1mmTcp.readAll();
        if (m_n1mmTcpBuffer.size() > 1048576) { stopN1mmRuntime(); return; }
        while (m_n1mmTcpBuffer.size() >= 4) {
            const auto *p = reinterpret_cast<const unsigned char *>(m_n1mmTcpBuffer.constData());
            const quint32 size = (quint32(p[0]) << 24) | (quint32(p[1]) << 16) | (quint32(p[2]) << 8) | quint32(p[3]);
            if (size == 0 || size > 65536) { stopN1mmRuntime(); return; }
            if (m_n1mmTcpBuffer.size() < qsizetype(size + 4)) return;
            const QByteArray packet = m_n1mmTcpBuffer.mid(4, size);
            m_n1mmTcpBuffer.remove(0, size + 4);
            if (!ingestN1mmPacket(m_n1mmTcpPeerId, packet)) { stopN1mmRuntime(); return; }
        }
    });
    connect(&m_n1mmTcp, &QTcpSocket::disconnected, this, [this] {
        if (!m_n1mmTcpPeerId.isEmpty()) updateN1mmPeerLifecycle(m_n1mmTcpPeerId, "DISCONNECTED");
        m_n1mmTcpPeerId.clear(); m_n1mmTcpBuffer.clear();
    });
    m_n1mmTcp.connectToHost(endpoint.host(), quint16(endpoint.port()));
    return true;
}

void DesktopParityPlatform::stopN1mmRuntime() {
    m_n1mmUdp.close();
    if (m_n1mmTcp.state() != QAbstractSocket::UnconnectedState) m_n1mmTcp.abort();
    m_n1mmTcpBuffer.clear(); m_n1mmTcpPeerId.clear();
    m_n1mmState = "DISABLED / loopback / untrusted / unarmed";
    emit workflowStateChanged();
}

bool DesktopParityPlatform::ingestN1mmPacket(const QString &peerId, const QByteArray &packet) {
    const QVariantMap parsed = parseN1mmPacket(packet);
    if (parsed.isEmpty()) return false;
    StoreSpec *contest = store("Contest"); if (!contest) return false;
    QSqlQuery peer(contest->database);
    peer.prepare("SELECT trusted FROM n1mm_peer WHERE id=?"); peer.addBindValue(peerId.trimmed());
    if (!peer.exec() || !peer.next()) return false;
    const QString digest = parsed.value("dedupeKey").toString();
    QSqlQuery event(contest->database);
    event.prepare("INSERT OR IGNORE INTO n1mm_event(id,peer_id,event_type,payload_digest,policy,received_at) VALUES(?,?,?,?,?,?)");
    event.addBindValue(QUuid::createUuid().toString(QUuid::WithoutBraces)); event.addBindValue(peerId.trimmed());
    event.addBindValue(parsed.value("type")); event.addBindValue(digest); event.addBindValue("REVIEW_ONLY");
    event.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!event.exec()) return false;
    updateN1mmPeerLifecycle(peerId, "HEARTBEAT");
    if (event.numRowsAffected() == 1)
        setReview(QStringLiteral("N1MM %1 received from %2 · review-only staging; no radio, Keyer, Digi or Chaser action")
                      .arg(parsed.value("type").toString(), peerId.trimmed()));
    return true;
}

QByteArray DesktopParityPlatform::frameN1mmTcpPacket(const QByteArray &packet) const {
    if (parseN1mmPacket(packet).isEmpty()) return {};
    const quint32 size = quint32(packet.size());
    QByteArray framed(4, Qt::Uninitialized);
    framed[0] = char((size >> 24) & 0xff); framed[1] = char((size >> 16) & 0xff);
    framed[2] = char((size >> 8) & 0xff); framed[3] = char(size & 0xff);
    return framed + packet;
}

QVariantList DesktopParityPlatform::parseN1mmTcpFrames(const QByteArray &frames) const {
    if (frames.size() > 1048576) return {};
    QVariantList result; qsizetype offset = 0;
    while (offset < frames.size()) {
        if (frames.size() - offset < 4) return {};
        const auto *p = reinterpret_cast<const unsigned char *>(frames.constData() + offset);
        const quint32 size = (quint32(p[0]) << 24) | (quint32(p[1]) << 16) | (quint32(p[2]) << 8) | quint32(p[3]);
        offset += 4;
        if (size == 0 || size > 65536 || size > quint32(frames.size() - offset)) return {};
        const QVariantMap packet = parseN1mmPacket(frames.mid(offset, size));
        if (packet.isEmpty()) return {};
        result << packet; offset += size;
    }
    return result;
}

void DesktopParityPlatform::setScpEndpointForTest(const QUrl &endpoint) { m_scpEndpoint = endpoint; }

bool DesktopParityPlatform::importScpPayloadForTest(const QByteArray &payload, const QUrl &source,
                                                    qint64 sourceDate, QString *error) {
    return importScpPayload(payload, source, sourceDate, error);
}

bool DesktopParityPlatform::importScpPayload(const QByteArray &payload, const QUrl &source,
                                             qint64 sourceDate, QString *error) {
    if (payload.isEmpty() || payload.size() > 8 * 1024 * 1024 || !source.isValid()) {
        if (error) *error = "SCP payload/source invalid";
        return false;
    }
    QSet<QString> unique;
    for (QByteArray token : payload.split('\n')) {
        token = token.trimmed().toUpper();
        if (token.endsWith('\r')) token.chop(1);
        if (token.isEmpty()) continue;
        const QString call = normalizedCallsign(QString::fromLatin1(token));
        if (call.isEmpty() || call.toLatin1() != token || unique.size() >= 1000000) {
            if (error) *error = "SCP contains an invalid or excessive callsign set";
            return false;
        }
        unique.insert(call);
    }
    if (unique.isEmpty()) {
        if (error) *error = "SCP has no callsigns";
        return false;
    }
    StoreSpec *contest = store("Contest");
    if (!contest || !contest->database.transaction()) {
        if (error) *error = "Contest store unavailable";
        return false;
    }
    QSqlQuery query(contest->database);
    if (!query.exec("DELETE FROM scp_callsign_next")) { contest->database.rollback(); return false; }
    query.prepare("INSERT INTO scp_callsign_next(callsign) VALUES(?)");
    for (const QString &call : std::as_const(unique)) {
        query.bindValue(0, call);
        if (!query.exec()) {
            if (error) *error = query.lastError().text();
            contest->database.rollback();
            return false;
        }
    }
    if (!query.exec("DELETE FROM scp_callsign") || !query.exec("INSERT INTO scp_callsign SELECT callsign FROM scp_callsign_next")) {
        if (error) *error = query.lastError().text();
        contest->database.rollback();
        return false;
    }
    query.prepare("INSERT INTO scp_manifest(id,source_url,source_date,digest,row_count,updated_at) VALUES(1,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET source_url=excluded.source_url,source_date=excluded.source_date,digest=excluded.digest,row_count=excluded.row_count,updated_at=excluded.updated_at");
    query.addBindValue(source.toString()); query.addBindValue(sourceDate);
    query.addBindValue(QString::fromLatin1(QCryptographicHash::hash(payload, QCryptographicHash::Sha256).toHex()));
    query.addBindValue(unique.size()); query.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!query.exec() || !contest->database.commit()) {
        if (error) *error = query.lastError().text();
        contest->database.rollback();
        return false;
    }
    m_scpState = QStringLiteral("READY / %1 calls / %2").arg(unique.size()).arg(QDateTime::fromSecsSinceEpoch(sourceDate, QTimeZone::UTC).date().toString(Qt::ISODate));
    emit workflowStateChanged();
    return true;
}

bool DesktopParityPlatform::refreshScp() {
    const bool productionSecure = m_scpEndpoint.scheme() == "https";
    const bool loopbackTest = m_scpEndpoint.scheme() == "http" &&
        (m_scpEndpoint.host() == "127.0.0.1" || m_scpEndpoint.host() == "::1" ||
         m_scpEndpoint.host() == "localhost");
    if (m_scpReply || !m_scpEndpoint.isValid() || (!productionSecure && !loopbackTest)) return false;
    QNetworkRequest request(m_scpEndpoint);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    request.setTransferTimeout(20000);
    m_scpBody.clear(); m_scpState = "REFRESHING / last-good retained"; emit workflowStateChanged();
    m_scpReply = m_network.get(request);
    connect(m_scpReply, &QNetworkReply::readyRead, this, [this] {
        if (!m_scpReply) return;
        m_scpBody += m_scpReply->readAll();
        if (m_scpBody.size() > 8 * 1024 * 1024) m_scpReply->abort();
    });
    connect(m_scpReply, &QNetworkReply::finished, this, &DesktopParityPlatform::finishScpRequest);
    return true;
}

void DesktopParityPlatform::finishScpRequest() {
    if (!m_scpReply) return;
    m_scpBody += m_scpReply->readAll();
    const int status = m_scpReply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    QString error = m_scpReply->errorString();
    const QDateTime modified = m_scpReply->header(QNetworkRequest::LastModifiedHeader).toDateTime();
    const bool accepted = m_scpReply->error() == QNetworkReply::NoError && status == 200 &&
        importScpPayload(m_scpBody, m_scpReply->url(),
                         modified.isValid() ? modified.toSecsSinceEpoch() : QDateTime::currentSecsSinceEpoch(),
                         &error);
    m_scpReply->deleteLater(); m_scpReply = nullptr; m_scpBody.clear();
    if (!accepted) { m_scpState = QStringLiteral("ERROR / last-good retained / %1").arg(sanitizedNetworkError(error).left(120)); emit workflowStateChanged(); }
}

QVariantMap DesktopParityPlatform::scpStatus() const {
    const StoreSpec *contest = store("Contest"); if (!contest) return {{"state", "UNAVAILABLE"}};
    QSqlQuery query(contest->database);
    if (!query.exec("SELECT source_url,source_date,digest,row_count,updated_at FROM scp_manifest WHERE id=1") || !query.next())
        return {{"state", "EMPTY"}, {"rowCount", 0}};
    return {{"state", "READY"}, {"sourceUrl", query.value(0)}, {"sourceDate", query.value(1)},
            {"digest", query.value(2)}, {"rowCount", query.value(3)}, {"updatedAt", query.value(4)}};
}

QVariantMap DesktopParityPlatform::scpLookup(const QString &partial, int limit) const {
    const QString needle = normalizedCallsign(partial);
    if (needle.isEmpty() || limit < 1 || limit > 50) return {};
    const StoreSpec *contest = store("Contest"); if (!contest) return {};
    QSqlQuery exact(contest->database); exact.prepare("SELECT 1 FROM scp_callsign WHERE callsign=?"); exact.addBindValue(needle);
    const bool found = exact.exec() && exact.next();
    QSqlQuery query(contest->database);
    query.prepare("SELECT callsign FROM scp_callsign WHERE callsign LIKE ? ORDER BY length(callsign),callsign LIMIT ?");
    query.addBindValue(needle + "%"); query.addBindValue(limit);
    if (!query.exec()) return {};
    QVariantList suggestions; while (query.next()) suggestions << query.value(0).toString();
    return {{"query", needle}, {"exact", found}, {"likelyBust", !found && !suggestions.isEmpty()}, {"suggestions", suggestions}};
}

QVariantMap DesktopParityPlatform::computeEmpiricalOutlook(const QVariantList &evidence,
                                                            int windowMinutes) const {
    if (!QList<int>{30, 60, 120}.contains(windowMinutes)) return {};
    QHash<QString, QVariantMap> grouped;
    for (const QVariant &entry : evidence) {
        const QVariantMap item = entry.toMap();
        const QString band = item.value("band").toString();
        const qint64 age = QDateTime::currentSecsSinceEpoch() - item.value("observedUtc").toLongLong();
        if (band.isEmpty() || age < 0 || age > 56LL * 86400) continue;
        QVariantMap state = grouped.value(band, {{"support", 0.0}, {"samples", 0}, {"verified", 0}});
        const double decay = std::exp(-double(age) / (14.0 * 86400.0));
        state["support"] = state.value("support").toDouble() + std::clamp(item.value("weight", 1.0).toDouble(), 0.0, 4.0) * decay;
        state["samples"] = state.value("samples").toInt() + 1;
        if (item.value("verification").toString() == "HIT") state["verified"] = state.value("verified").toInt() + 1;
        grouped[band] = state;
    }
    QVariantList bands;
    for (auto it = grouped.cbegin(); it != grouped.cend(); ++it) {
        const int samples = it->value("samples").toInt(), verified = it->value("verified").toInt();
        const double support = it->value("support").toDouble();
        const double confidence = std::min(0.95, (1.0 - std::exp(-samples / 12.0)) * (0.5 + 0.5 * verified / std::max(1, samples)));
        bands << QVariantMap{{"band", it.key()}, {"windowMinutes", windowMinutes}, {"support", support},
                             {"confidence", confidence}, {"samples", samples},
                             {"reason", QStringLiteral("%1 weighted observations; empirical only").arg(samples)}};
    }
    std::sort(bands.begin(), bands.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value("support").toDouble() > b.toMap().value("support").toDouble();
    });
    return {{"windowMinutes", windowMinutes}, {"bands", bands}, {"claim", "EMPIRICAL_NOT_P533"}};
}

QVariantList DesktopParityPlatform::evaluateBandMap(const QVariantList &spots) const {
    QVariantList result;
    for (const QVariant &entry : spots) {
        QVariantMap spot = entry.toMap();
        const QString call = normalizedCallsign(spot.value("callsign").toString());
        const quint64 frequency = spot.value("frequencyHz").toULongLong();
        if (call.isEmpty() || frequency < 100000) continue;
        int priority = spot.value("watchlisted").toBool() ? 50 : 0;
        if (spot.value("needed").toBool()) priority += 40;
        if (spot.value("contestOpportunity").toBool()) priority += 25;
        if (spot.value("dxChaserEligible").toBool()) priority += 20;
        priority += qRound(std::clamp(spot.value("empiricalConfidence").toDouble(), 0.0, 1.0) * 15.0);
        if (spot.value("worked").toBool()) priority -= 20;
        if (spot.value("hidden").toBool()) continue;
        spot["callsign"] = call; spot["priority"] = priority;
        spot["state"] = spot.value("confirmed").toBool() ? "CONFIRMED" : spot.value("worked").toBool() ? "WORKED" : "NEEDED";
        result << spot;
    }
    std::sort(result.begin(), result.end(), [](const QVariant &a, const QVariant &b) {
        const QVariantMap left = a.toMap(), right = b.toMap();
        const int score = left.value("priority").toInt() - right.value("priority").toInt();
        return score == 0 ? left.value("frequencyHz").toULongLong() < right.value("frequencyHz").toULongLong() : score > 0;
    });
    return result;
}

bool DesktopParityPlatform::refreshSpotProjections(const QVariantList &spots) {
    if (spots.size() > 20000) return false;
    const QVariantList ranked = evaluateBandMap(spots);
    QVariantList bandRows, dxRows;
    bandRows.reserve(ranked.size());
    dxRows.reserve(ranked.size());
    for (const QVariant &entry : ranked) {
        const QVariantMap spot = entry.toMap();
        const QString call = spot.value("callsign").toString();
        const quint64 frequency = spot.value("frequencyHz").toULongLong();
        const QString mode = spot.value("mode").toString();
        const QString state = spot.value("state").toString();
        const int priority = spot.value("priority").toInt();
        bandRows << functionalRow(
            QStringLiteral("%1:%2").arg(call).arg(frequency), call, mode, state,
            QStringLiteral("%1 · priority %2 · %3")
                .arg(spot.value("band").toString()).arg(priority)
                .arg(spot.value("comment").toString().left(120)),
            "Band Map", frequency, true,
            spot.value("receivedAt").toLongLong());
        dxRows << functionalRow(
            QStringLiteral("dx:%1:%2").arg(call).arg(frequency), call,
            QStringLiteral("%1 · %2").arg(frequency / 1000.0, 0, 'f', 1).arg(mode),
            state, spot.value("comment").toString().left(180), "DX", frequency,
            true, spot.value("receivedAt").toLongLong());
    }
    m_bandMapRows.replace(bandRows);
    m_dxWorkspaceRows.replace(dxRows);
    return true;
}

QVariantMap DesktopParityPlatform::predictSatellitePasses(const QString &name,
                                                           const QString &line1,
                                                           const QString &line2,
                                                           double latitude,
                                                           double longitude,
                                                           double altitudeKm,
                                                           qint64 startUtc,
                                                           qint64 endUtc) const {
    if (line1.size() < 60 || line2.size() < 60 || latitude < -90 || latitude > 90 ||
        longitude < -180 || longitude > 180 || endUtc <= startUtc || endUtc - startUtc > 14LL * 86400) return {};
    QByteArray output(1024 * 1024, '\0');
    const QByteArray encodedName = name.toUtf8(), one = line1.toLatin1(), two = line2.toLatin1();
    const int count = shackcq_satellite_passes_json(output.data(), size_t(output.size()), "TLE",
                                               encodedName.constData(), one.constData(), two.constData(),
                                               startUtc, endUtc, 14LL * 86400, latitude, longitude,
                                               altitudeKm, 0.0, 0.0, 60, 64);
    if (count < 0) return {};
    QJsonParseError parse;
    const QJsonDocument document = QJsonDocument::fromJson(output.left(count), &parse);
    if (parse.error != QJsonParseError::NoError) return {};
    return {{"name", name.left(80)}, {"passes", document.toVariant()}, {"receiveOnly", true},
            {"automaticDoppler", false}, {"rotatorMovement", false}};
}

bool DesktopParityPlatform::calculateSatellitePasses(
    const QString &name, const QString &line1, const QString &line2,
    double latitude, double longitude, double altitudeKm, qint64 startUtc,
    qint64 endUtc) {
    const QVariantMap prediction =
        predictSatellitePasses(name, line1, line2, latitude, longitude,
                               altitudeKm, startUtc, endUtc);
    if (prediction.isEmpty()) return false;
    const QVariant raw = prediction.value("passes");
    QVariantList passes = raw.toList();
    if (passes.isEmpty() && raw.canConvert<QVariantMap>())
        passes = raw.toMap().value("passes").toList();
    QVariantList rows;
    rows.reserve(passes.size());
    int index = 0;
    for (const QVariant &entry : passes) {
        const QVariantMap pass = entry.toMap();
        const qint64 aos = pass.value("aosUtc", pass.value("startUtc")).toLongLong();
        const double maximum = pass.value("maximumElevationDeg",
                                          pass.value("maxElevationDeg")).toDouble();
        rows << functionalRow(
            QStringLiteral("%1:%2:%3").arg(name.left(40)).arg(aos).arg(index++),
            name.left(80),
            QStringLiteral("AOS %1 UTC · max %2°")
                .arg(QDateTime::fromSecsSinceEpoch(aos, QTimeZone::UTC)
                         .toString("yyyy-MM-dd HH:mm"))
                .arg(maximum, 0, 'f', 1),
            "LOCAL_SGP4_RX_ONLY", "No automatic Doppler, TX or rotator movement",
            "Satellite", maximum, true, aos);
    }
    m_satellitePasses.replace(rows);
    return true;
}

bool DesktopParityPlatform::queueGroupsDraft(const QVariantMap &input) {
    const QString groupId = input.value("groupId").toString().trimmed();
    const QString subject = input.value("subject").toString().trimmed();
    const QString body = input.value("body").toString();
    if (groupId.isEmpty() || subject.isEmpty() || subject.size() > 200 || body.isEmpty() || body.size() > 1024 * 1024) return false;
    StoreSpec *groups = store("Groups.io"); if (!groups) return false;
    const QString draftId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    const QString outboxId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    if (!groups->database.transaction()) return false;
    QSqlQuery draft(groups->database);
    draft.prepare("INSERT INTO drafts(id,group_id,topic_id,subject,body,updated_at) VALUES(?,?,?,?,?,?)");
    draft.addBindValue(draftId); draft.addBindValue(groupId); draft.addBindValue(input.value("topicId"));
    draft.addBindValue(subject); draft.addBindValue(body); draft.addBindValue(QDateTime::currentSecsSinceEpoch());
    QSqlQuery outbox(groups->database);
    outbox.prepare("INSERT INTO outbox(id,draft_id,intent,state,attempts,created_at) VALUES(?,?,'POST','PENDING',0,?)");
    outbox.addBindValue(outboxId); outbox.addBindValue(draftId); outbox.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!draft.exec() || !outbox.exec() || !groups->database.commit()) { groups->database.rollback(); return false; }
    QVariantList rows;
    for (int index = 0; index < m_groupsOutbox.rowCount(); ++index) rows << m_groupsOutbox.item(index);
    rows.prepend(functionalRow(outboxId, subject, groupId, "PENDING", "Explicit send required; ambiguous writes are reconciled", "Groups.io", draftId));
    m_groupsOutbox.replace(rows);
    return true;
}

bool DesktopParityPlatform::reconcileGroupsDelivery(const QString &outboxId, const QString &state,
                                                     const QString &serverId) {
    static const QSet<QString> allowed{"PENDING", "AMBIGUOUS", "DELIVERED", "MODERATION"};
    if (outboxId.isEmpty() || !allowed.contains(state)) return false;
    StoreSpec *groups = store("Groups.io"); if (!groups) return false;
    QSqlQuery query(groups->database);
    query.prepare("UPDATE outbox SET state=? WHERE id=?"); query.addBindValue(state); query.addBindValue(outboxId);
    if (!query.exec() || query.numRowsAffected() != 1) return false;
    QSqlQuery ledger(groups->database);
    ledger.prepare("INSERT OR REPLACE INTO delivery_ledger(outbox_id,server_id,state,reconciled_at) VALUES(?,?,?,?)");
    ledger.addBindValue(outboxId); ledger.addBindValue(serverId.left(128)); ledger.addBindValue(state);
    ledger.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!ledger.exec()) return false;
    m_groupsOutbox.update(outboxId, {{"state", state}, {"detail", serverId.isEmpty() ? "No server identifier" : "Server delivery reconciled"}});
    return true;
}

void DesktopParityPlatform::setCredentialResolver(
    std::function<QString(const QString &)> resolver) {
    m_credentialResolver = std::move(resolver);
}

bool DesktopParityPlatform::setGroupsCredentialAlias(const QString &alias) {
    const QString value = alias.trimmed();
    static const QRegularExpression valid("^[A-Za-z0-9._-]{1,80}$");
    if (!value.isEmpty() && !valid.match(value).hasMatch()) return false;
    if (m_groupsReply) return false;
    if (m_groupsCredentialAlias == value) return true;
    m_groupsCredentialAlias = value;
    emit groupsConfigurationChanged();
    return true;
}

QVariantMap DesktopParityPlatform::groupsConfiguration() const {
    return {{"schemaVersion", 1},
            {"credentialAlias", m_groupsCredentialAlias},
            {"autoRefresh", false},
            {"pendingSend", false}};
}

bool DesktopParityPlatform::restoreGroupsConfiguration(
    const QVariantMap &section, QString *error) {
    if (section.value("schemaVersion", 1).toInt() > 1) {
        if (error) *error = "groupsio schema is newer than supported schema 1";
        return false;
    }
    if (!setGroupsCredentialAlias(section.value("credentialAlias").toString())) {
        if (error) *error = "Invalid Groups.io credential alias";
        return false;
    }
    return true;
}

void DesktopParityPlatform::setGroupsEndpointForTest(const QUrl &endpoint) {
    if ((endpoint.scheme() == "http" || endpoint.scheme() == "https") &&
        (endpoint.host() == "127.0.0.1" || endpoint.host() == "::1" ||
         endpoint.host() == "localhost"))
        m_groupsEndpoint = endpoint;
}

bool DesktopParityPlatform::beginGroupsRequest(
    const QString &phase, const QString &path, const QVariantMap &query,
    const QVariantMap &form, const QString &outboxId) {
    if (m_groupsReply || !m_credentialResolver ||
        m_groupsCredentialAlias.isEmpty() || m_closed) return false;
    const QString bearer = m_credentialResolver(m_groupsCredentialAlias);
    if (bearer.trimmed().isEmpty() || bearer.size() > 4096) return false;
    const bool productionSecure = m_groupsEndpoint.scheme() == "https";
    const bool loopbackTest =
        m_groupsEndpoint.scheme() == "http" &&
        (m_groupsEndpoint.host() == "127.0.0.1" ||
         m_groupsEndpoint.host() == "::1" ||
         m_groupsEndpoint.host() == "localhost");
    if (!productionSecure && !loopbackTest) return false;
    QUrl target = m_groupsEndpoint;
    target.setPath(target.path().remove(QRegularExpression("/+$")) + path);
    QUrlQuery values;
    for (auto it = query.cbegin(); it != query.cend(); ++it)
        values.addQueryItem(it.key(), it.value().toString());
    target.setQuery(values);
    QNetworkRequest request(target);
    request.setRawHeader("Accept", "application/json");
    request.setRawHeader("Authorization", "Bearer " + bearer.toUtf8());
    request.setTransferTimeout(30000);
    m_groupsPhase = phase;
    m_groupsScopeId = phase == "topics" ? query.value("group_id").toString()
                                         : query.value("topic_id").toString();
    m_groupsOutboxId = outboxId;
    m_groupsBody.clear();
    if (form.isEmpty()) {
        m_groupsReply = m_network.get(request);
    } else {
        QUrlQuery encoded;
        for (auto it = form.cbegin(); it != form.cend(); ++it)
            encoded.addQueryItem(it.key(), it.value().toString());
        request.setHeader(QNetworkRequest::ContentTypeHeader,
                          "application/x-www-form-urlencoded");
        m_groupsReply =
            m_network.post(request, encoded.toString(QUrl::FullyEncoded).toUtf8());
    }
    connect(m_groupsReply, &QNetworkReply::readyRead, this, [this] {
        if (!m_groupsReply) return;
        m_groupsBody += m_groupsReply->readAll();
        if (m_groupsBody.size() > 2 * 1024 * 1024) m_groupsReply->abort();
    });
    connect(m_groupsReply, &QNetworkReply::finished, this,
            &DesktopParityPlatform::finishGroupsRequest);
    return true;
}

bool DesktopParityPlatform::refreshGroupsMemberships() {
    return beginGroupsRequest("memberships", "/getsubs",
                              {{"limit", 100}});
}

bool DesktopParityPlatform::refreshGroupsTopics(const QString &groupId) {
    if (groupId.trimmed().isEmpty()) return false;
    return beginGroupsRequest("topics", "/gettopics",
                              {{"group_id", groupId.trimmed()},
                               {"sort_dir", "desc"}, {"limit", 100}});
}

bool DesktopParityPlatform::refreshGroupsMessages(const QString &groupId,
                                                  const QString &topicId) {
    if (groupId.trimmed().isEmpty() || topicId.trimmed().isEmpty()) return false;
    return beginGroupsRequest("messages", "/getmessages",
                              {{"group_id", groupId.trimmed()},
                               {"topic_id", topicId.trimmed()},
                               {"limit", 100}});
}

bool DesktopParityPlatform::sendGroupsOutbox(const QString &outboxId) {
    StoreSpec *groups = store("Groups.io");
    if (!groups || outboxId.trimmed().isEmpty()) return false;
    QSqlQuery query(groups->database);
    query.prepare("SELECT d.group_id FROM outbox o JOIN drafts d ON d.id=o.draft_id "
                  "WHERE o.id=? AND o.state='PENDING'");
    query.addBindValue(outboxId);
    if (!query.exec() || !query.next()) return false;
    QSqlQuery attempt(groups->database);
    attempt.prepare("UPDATE outbox SET attempts=attempts+1 WHERE id=?");
    attempt.addBindValue(outboxId);
    if (!attempt.exec()) return false;
    return beginGroupsRequest(
        "newdraft", "/newdraft", {},
        {{"group_id", query.value(0).toString()},
         {"draft_type", "draft_type_post"}},
        outboxId);
}

void DesktopParityPlatform::finishGroupsRequest() {
    if (!m_groupsReply) return;
    QNetworkReply *reply = m_groupsReply;
    m_groupsBody += reply->readAll();
    const QString phase = m_groupsPhase;
    const QString scopeId = m_groupsScopeId;
    const QString outboxId = m_groupsOutboxId;
    const bool transportOk =
        reply->error() == QNetworkReply::NoError &&
        reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() >= 200 &&
        reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() < 300 &&
        m_groupsBody.size() <= 2 * 1024 * 1024;
    reply->deleteLater();
    m_groupsReply.clear();
    QJsonParseError parse;
    const QJsonDocument document = QJsonDocument::fromJson(m_groupsBody, &parse);
    m_groupsBody.clear();
    if (!transportOk || parse.error != QJsonParseError::NoError ||
        !document.isObject()) {
        if (phase == "postdraft" && !outboxId.isEmpty())
            reconcileGroupsDelivery(outboxId, "AMBIGUOUS");
        setReview(QStringLiteral("Groups.io %1 failed; credential and response bodies are excluded")
                      .arg(phase.left(40)));
        return;
    }
    const QJsonObject root = document.object();
    if (root.value("object").toString() == "error") {
        setReview(QStringLiteral("Groups.io %1 rejected the request").arg(phase.left(40)));
        return;
    }
    StoreSpec *groups = store("Groups.io");
    if (!groups) return;
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    const QJsonArray data = root.value("data").toArray();
    if (data.size() > 1000) {
        setReview("Groups.io response exceeded the 1000-row page bound");
        return;
    }
    if (phase == "memberships") {
        QVariantList rows;
        if (!groups->database.transaction()) return;
        QSqlQuery insert(groups->database);
        insert.prepare("INSERT OR REPLACE INTO groups(id,name,role,refreshed_at) "
                       "VALUES(?,?,?,?)");
        for (const QJsonValue &entry : data) {
            const QJsonObject value = entry.toObject();
            const QString id =
                QString::number(value.value("group_id").toVariant().toLongLong());
            QString name = value.value("group_name").toString();
            if (name.isEmpty()) name = value.value("name").toString();
            if (id == "0" || name.isEmpty()) continue;
            const QString role =
                value.value("status").toString(
                    value.value("subscription_status").toString());
            insert.addBindValue(id); insert.addBindValue(name.left(160));
            insert.addBindValue(role.left(80)); insert.addBindValue(now);
            if (!insert.exec()) { groups->database.rollback(); return; }
            insert.finish();
            rows << functionalRow(id, name.left(160), role.left(80), "CURRENT",
                                  "Authenticated membership; explicit topic refresh",
                                  "Groups.io", {}, true, now);
        }
        if (!groups->database.commit()) return;
        m_groupsMemberships.replace(rows);
    } else if (phase == "topics") {
        QVariantList rows;
        if (!groups->database.transaction()) return;
        QSqlQuery insert(groups->database);
        insert.prepare("INSERT OR REPLACE INTO topics(id,group_id,subject,updated_at,"
                       "message_count,closed) VALUES(?,?,?,?,?,?)");
        for (const QJsonValue &entry : data) {
            const QJsonObject value = entry.toObject();
            qint64 topicId = value.value("id").toVariant().toLongLong();
            if (topicId <= 0)
                topicId = value.value("topic_id").toVariant().toLongLong();
            const QString id = QString::number(topicId);
            QString subject = value.value("subject").toString();
            if (subject.isEmpty()) subject = value.value("title").toString();
            if (id == "0" || subject.isEmpty()) continue;
            const qint64 updated =
                value.value("updated").toVariant().toLongLong();
            const int count = value.value("message_count").toInt();
            const bool closed = value.value("closed").toBool();
            insert.addBindValue(id); insert.addBindValue(scopeId);
            insert.addBindValue(subject.left(300)); insert.addBindValue(updated);
            insert.addBindValue(count); insert.addBindValue(closed);
            if (!insert.exec()) { groups->database.rollback(); return; }
            insert.finish();
            rows << functionalRow(id, subject.left(300),
                                  QStringLiteral("%1 messages").arg(count),
                                  closed ? "CLOSED" : "CURRENT",
                                  "Explicit message refresh", "Groups.io", count,
                                  true, updated);
        }
        if (!groups->database.commit()) return;
        m_groupsTopics.replace(rows);
    } else if (phase == "messages") {
        QVariantList rows;
        if (!groups->database.transaction()) return;
        QSqlQuery insert(groups->database);
        insert.prepare("INSERT OR REPLACE INTO messages(id,group_id,topic_id,subject,"
                       "sender,body,server_timestamp,delivery_state) "
                       "VALUES(?,?,?,?,?,?,?,'REMOTE')");
        for (const QJsonValue &entry : data) {
            const QJsonObject value = entry.toObject();
            qint64 number = value.value("message_number").toVariant().toLongLong();
            if (number <= 0) number = value.value("id").toVariant().toLongLong();
            const QString id = QStringLiteral("%1:%2").arg(scopeId).arg(number);
            const QString groupId = value.value("group_id").toVariant().toString();
            const QString subject = value.value("subject").toString().left(300);
            const QString sender =
                value.value("author_name").toString(
                    value.value("sender").toString()).left(160);
            const QString body =
                value.value("body_plain").toString(
                    value.value("body").toString()).left(1024 * 1024);
            const qint64 created = value.value("created").toVariant().toLongLong();
            if (number <= 0 || subject.isEmpty()) continue;
            insert.addBindValue(id); insert.addBindValue(groupId);
            insert.addBindValue(scopeId); insert.addBindValue(subject);
            insert.addBindValue(sender); insert.addBindValue(body);
            insert.addBindValue(created);
            if (!insert.exec()) { groups->database.rollback(); return; }
            insert.finish();
            rows << functionalRow(id, subject, sender, "CURRENT",
                                  body.left(240), "Groups.io", number, true,
                                  created);
        }
        if (!groups->database.commit()) return;
        m_groupsMessages.replace(rows);
    } else if (phase == "newdraft") {
        m_groupsRemoteDraftId =
            root.value("id").toVariant().toLongLong();
        if (m_groupsRemoteDraftId <= 0)
            m_groupsRemoteDraftId =
                root.value("draft_id").toVariant().toLongLong();
        QSqlQuery draft(groups->database);
        draft.prepare("SELECT d.subject,d.body FROM outbox o JOIN drafts d "
                      "ON d.id=o.draft_id WHERE o.id=?");
        draft.addBindValue(outboxId);
        if (!draft.exec() || !draft.next() || m_groupsRemoteDraftId <= 0) return;
        const QString safeHtml =
            draft.value(1).toString().toHtmlEscaped().replace("\n", "<br>");
        beginGroupsRequest("updatedraft", "/updatedraft", {},
                           {{"draft_id", m_groupsRemoteDraftId},
                            {"subject", draft.value(0).toString()},
                            {"body", safeHtml}, {"body_type", "html"}},
                           outboxId);
    } else if (phase == "updatedraft") {
        beginGroupsRequest("postdraft", "/postdraft", {},
                           {{"draft_id", m_groupsRemoteDraftId}}, outboxId);
    } else if (phase == "postdraft") {
        const bool moderation =
            root.value("pending_moderation").toBool() ||
            root.value("post_status").toString().contains(
                "moderation", Qt::CaseInsensitive);
        const QString serverId =
            root.value("message_id").toVariant().toString();
        reconcileGroupsDelivery(outboxId,
                                moderation ? "MODERATION" : "DELIVERED",
                                serverId);
    }
}

bool DesktopParityPlatform::injectTestAlert(const QString &profile, const QString &title,
                                            const QString &body) {
    if (!m_demoMode || !QSet<QString>{"DAY", "NIGHT", "FIELD"}.contains(profile) ||
        title.trimmed().isEmpty() || body.trimmed().isEmpty()) return false;
    const bool critical = title.contains("STOP", Qt::CaseInsensitive) || title.contains("SAFETY", Qt::CaseInsensitive);
    emit notificationRequested(title.left(120), body.left(500), critical);
    return true;
}

void DesktopParityPlatform::refreshOwnerHealth() {
    QVariantList rows;
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    for (int index = 0; index < m_closureLedger.rowCount(); ++index) {
        const QVariantMap item = m_closureLedger.item(index);
        rows << functionalRow(item.value("key").toString(), item.value("title").toString(),
                              "one production owner", item.value("state").toString(),
                              item.value("detail").toString(), "Health", {}, true, now);
    }
    m_ownerHealth.replace(rows);
}

void DesktopParityPlatform::functionalStop() {
    stopDigi();
    stopN1mmRuntime();
    m_activeContestSession.clear();
    m_contestState = "INACTIVE";
    m_operatingContext["contestSession"] = QString{};
    m_operatingContext["digiSession"] = QString{};
    m_operatingContext["transmitAccepted"] = false;
    m_operatingContext["rotatorMovementAccepted"] = false;
    m_operatingContext["generation"] = m_operatingContext.value("generation").toULongLong() + 1;
    emit workflowStateChanged();
    emit operatingContextChanged();
}

} // namespace shackcq::desktop
