#include "shackcq/desktop/DesktopModels.hpp"

#include <QRegularExpression>
#include <algorithm>

namespace shackcq::desktop {

QString normalizedCallsign(const QString &value) {
    return value.trimmed().toUpper().remove(QRegularExpression(QStringLiteral("[^A-Z0-9/]+")));
}

QString canonicalAdifValue(const QString &value) {
    return value.trimmed().replace(QRegularExpression(QStringLiteral("\\s+")), QStringLiteral(" "));
}

QString bandForFrequency(qint64 hz) {
    struct Range { qint64 low; qint64 high; const char *band; };
    static constexpr Range ranges[] = {
        {1800000, 2000000, "160m"}, {3500000, 4000000, "80m"},
        {5330500, 5406500, "60m"}, {7000000, 7300000, "40m"},
        {10100000, 10150000, "30m"}, {14000000, 14350000, "20m"},
        {18068000, 18168000, "17m"}, {21000000, 21450000, "15m"},
        {24890000, 24990000, "12m"}, {28000000, 29700000, "10m"},
        {50000000, 54000000, "6m"}, {144000000, 148000000, "2m"},
        {430000000, 450000000, "70cm"}
    };
    for (const auto &range : ranges) {
        if (hz >= range.low && hz <= range.high) return QString::fromLatin1(range.band);
    }
    return QStringLiteral("out-of-plan");
}

SpotRepository::SpotRepository(QObject *parent) : QAbstractListModel(parent) {}

int SpotRepository::rowCount(const QModelIndex &parent) const {
    return parent.isValid() ? 0 : m_spots.size();
}

QVariant SpotRepository::data(const QModelIndex &index, int role) const {
    if (!index.isValid() || index.row() < 0 || index.row() >= m_spots.size()) return {};
    const auto &spot = m_spots.at(index.row());
    switch (role) {
    case FrequencyRole: return QVariant::fromValue<qulonglong>(spot.frequencyHz);
    case CallsignRole: return spot.callsign;
    case SpotterRole: return spot.spotter;
    case CommentRole: return spot.comment;
    case BandRole: return spot.band;
    case ModeRole: return spot.mode;
    case SourceRole: return spot.source;
    case ReceivedAtRole: return spot.receivedAt;
    case AgeSecondsRole: return qMax<qint64>(0, QDateTime::currentSecsSinceEpoch() - spot.receivedAt);
    case WatchlistedRole: return spot.watchlisted;
    case WorkedRole: return spot.worked;
    case ConfirmedRole: return spot.confirmed;
    default: return {};
    }
}

QHash<int, QByteArray> SpotRepository::roleNames() const {
    return {{FrequencyRole, "frequencyHz"}, {CallsignRole, "callsign"},
            {SpotterRole, "spotter"}, {CommentRole, "comment"}, {BandRole, "band"},
            {ModeRole, "mode"}, {SourceRole, "source"}, {ReceivedAtRole, "receivedAt"},
            {AgeSecondsRole, "ageSeconds"}, {WatchlistedRole, "watchlisted"},
            {WorkedRole, "worked"}, {ConfirmedRole, "confirmed"}};
}

void SpotRepository::ingest(SpotObservation spot) {
    spot.callsign = normalizedCallsign(spot.callsign);
    if (spot.callsign.isEmpty() || spot.frequencyHz < 1000000ULL || spot.frequencyHz > 10500000000ULL) return;
    const QString key = QStringLiteral("%1|%2|%3").arg(spot.callsign).arg(spot.frequencyHz / 100).arg(spot.source);
    if (const auto found = m_keys.constFind(key); found != m_keys.cend()) {
        const int row = found.value();
        m_spots[row] = std::move(spot);
        emit dataChanged(index(row), index(row));
        return;
    }
    if (m_spots.size() >= MaximumObservations) {
        beginRemoveRows({}, 0, 0);
        m_spots.removeFirst();
        endRemoveRows();
        m_keys.clear();
        for (int row = 0; row < m_spots.size(); ++row) {
            const auto &existing = m_spots.at(row);
            m_keys.insert(QStringLiteral("%1|%2|%3").arg(existing.callsign).arg(existing.frequencyHz / 100).arg(existing.source), row);
        }
    }
    const int row = m_spots.size();
    beginInsertRows({}, row, row);
    m_spots.push_back(std::move(spot));
    m_keys.insert(key, row);
    endInsertRows();
    emit countChanged();
}

void SpotRepository::clearExpired(qint64 maximumAgeSeconds) {
    const qint64 maximumAge = qBound(qint64{1}, maximumAgeSeconds, qint64{604800});
    const qint64 cutoff = QDateTime::currentSecsSinceEpoch() - maximumAge;
    beginResetModel();
    m_spots.erase(std::remove_if(m_spots.begin(), m_spots.end(),
                                 [cutoff](const SpotObservation &spot) { return spot.receivedAt < cutoff; }),
                  m_spots.end());
    m_keys.clear();
    for (int row = 0; row < m_spots.size(); ++row) {
        const auto &spot = m_spots.at(row);
        m_keys.insert(QStringLiteral("%1|%2|%3").arg(spot.callsign).arg(spot.frequencyHz / 100).arg(spot.source), row);
    }
    endResetModel();
    emit countChanged();
}

QVariantMap SpotRepository::exact(int row) const {
    if (row < 0 || row >= m_spots.size()) return {};
    const auto &spot = m_spots.at(row);
    return {{"frequencyHz", QVariant::fromValue<qulonglong>(spot.frequencyHz)},
            {"callsign", spot.callsign}, {"spotter", spot.spotter}, {"comment", spot.comment},
            {"band", spot.band}, {"mode", spot.mode}, {"source", spot.source},
            {"receivedAt", spot.receivedAt}, {"watchlisted", spot.watchlisted},
            {"worked", spot.worked}, {"confirmed", spot.confirmed}};
}

} // namespace shackcq::desktop
