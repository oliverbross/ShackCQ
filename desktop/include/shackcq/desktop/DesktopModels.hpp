#pragma once

#include <QAbstractListModel>
#include <QDateTime>
#include <QJsonObject>
#include <QObject>
#include <QStringList>
#include <QVector>

namespace shackcq::desktop {

struct QsoRecord {
    QString id;
    QString callsign;
    qint64 frequencyHz{};
    qint64 frequencyRxHz{};
    QString band;
    QString bandRx;
    QString mode;
    QString submode;
    QString rstSent{"59"};
    QString rstReceived{"59"};
    QString grid;
    QString comment;
    QString stationProfileId;
    QString stationCallsign;
    QString operatorCallsign;
    QString dxcc;
    QString country;
    QString cqZone;
    QString ituZone;
    QString contestId;
    QString satelliteName;
    QString satelliteMode;
    QString potaRef;
    QString sotaRef;
    QString iota;
    QString wwffRef;
    QString qslReceived{"N"};
    QString lotwReceived{"N"};
    QString eqslReceived{"N"};
    QString qrzReceived{"N"};
    QString provenance{"local"};
    QString remoteId;
    qint64 createdAt{};
    qint64 updatedAt{};
    bool deleted{};
    QJsonObject extraAdif;
};

struct SpotObservation {
    quint64 frequencyHz{};
    QString callsign;
    QString spotter;
    QString comment;
    QString band;
    QString mode;
    QString source;
    qint64 receivedAt{};
    bool watchlisted{};
    bool worked{};
    bool confirmed{};
};

class SpotRepository final : public QAbstractListModel {
    Q_OBJECT
    Q_PROPERTY(int count READ rowCount NOTIFY countChanged)
public:
    enum Role {
        FrequencyRole = Qt::UserRole + 1,
        CallsignRole, SpotterRole, CommentRole, BandRole, ModeRole, SourceRole,
        ReceivedAtRole, AgeSecondsRole, WatchlistedRole, WorkedRole, ConfirmedRole
    };

    explicit SpotRepository(QObject *parent = nullptr);
    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;
    void ingest(SpotObservation spot);
    Q_INVOKABLE void clearExpired(qint64 maximumAgeSeconds);
    Q_INVOKABLE QVariantMap exact(int row) const;
    const QVector<SpotObservation> &observations() const { return m_spots; }

signals:
    void countChanged();

private:
    static constexpr int MaximumObservations = 20000;
    QVector<SpotObservation> m_spots;
    QHash<QString, int> m_keys;
};

QString normalizedCallsign(const QString &value);
QString bandForFrequency(qint64 frequencyHz);
QString canonicalAdifValue(const QString &value);

} // namespace shackcq::desktop
