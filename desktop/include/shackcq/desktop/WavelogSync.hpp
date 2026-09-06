#pragma once

#include "shackcq/desktop/QsoDatabase.hpp"

#include <QNetworkAccessManager>
#include <QJsonObject>
#include <QMap>
#include <QSet>
#include <QUrl>
#include <functional>
#include <optional>

namespace shackcq::desktop {

enum class SyncMode { Initial, Quick, Full };
enum class ConflictResolution { KeepLocal, KeepRemote, Merge };

struct WavelogBinding {
    QString id;
    QUrl serverUrl;
    QString credentialAlias;
    QString localStationProfileId;
    QString remoteStationId;
    bool canRead{};
    bool canWrite{};
};

struct CanonicalQso {
    QMap<QString, QString> fields;
    QByteArray encoded() const;
    QByteArray hash() const;
};

struct MergeResult {
    QString disposition;
    CanonicalQso merged;
    QStringList conflicts;
};

class WavelogEndpoint {
public:
    virtual ~WavelogEndpoint() = default;
    virtual void close() {}
    virtual QVariantMap capabilities(const QUrl &server, const QString &token) = 0;
    virtual QVariantList stations(const QUrl &server, const QString &token) = 0;
    virtual QVariantMap page(const WavelogBinding &binding, const QString &token, int page) = 0;
    virtual QVariantMap apply(const WavelogBinding &binding, const QString &token,
                              const QString &operation, const CanonicalQso &qso,
                              const QString &remoteId) = 0;
};

class QtWavelogEndpoint final : public QObject, public WavelogEndpoint {
    Q_OBJECT
public:
    explicit QtWavelogEndpoint(QObject *parent = nullptr);
    ~QtWavelogEndpoint() override { close(); }
    void close() override;
    QVariantMap capabilities(const QUrl &server, const QString &token) override;
    QVariantList stations(const QUrl &server, const QString &token) override;
    QVariantMap page(const WavelogBinding &binding, const QString &token, int page) override;
    QVariantMap apply(const WavelogBinding &binding, const QString &token,
                      const QString &operation, const CanonicalQso &qso,
                      const QString &remoteId) override;
    static QUrl normalizedRoot(const QUrl &server);
private:
    QVariantMap request(const QUrl &url, const QString &token, const QByteArray &method,
                        const QJsonObject &body = {});
    QNetworkAccessManager m_network;
    QSet<QNetworkReply *> m_replies;
    bool m_closed{};
};

class WavelogSyncEngine final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString state READ state NOTIFY stateChanged)
    Q_PROPERTY(int pendingCount READ pendingCount NOTIFY queueChanged)
    Q_PROPERTY(int conflictCount READ conflictCount NOTIFY queueChanged)
public:
    WavelogSyncEngine(QsoDatabase *database, QObject *parent = nullptr);
    void setEndpoint(WavelogEndpoint *endpoint) { m_endpoint = endpoint; if (m_closed && m_endpoint) m_endpoint->close(); }
    void setCredentialResolver(std::function<QString(const QString &)> resolver) { m_credentialResolver = std::move(resolver); }
    QString state() const { return m_state; }
    int pendingCount() const;
    int conflictCount() const;
    bool saveBinding(const WavelogBinding &binding, QString *error = nullptr);
    Q_INVOKABLE bool configureBinding(const QString &serverUrl, const QString &credentialAlias,
                                      const QString &localStationProfileId,
                                      const QString &remoteStationId,
                                      bool canWrite);
    std::optional<WavelogBinding> binding() const;
    bool enqueue(const QString &qsoId, const QString &operation, QString *error = nullptr);
    Q_INVOKABLE void synchronize(const QString &mode);
    Q_INVOKABLE void retryPending();
    Q_INVOKABLE void close();
    bool closed() const { return m_closed; }
    Q_INVOKABLE bool resolveConflict(const QString &id, const QString &resolution,
                                     const QVariantMap &merged = {});
    static CanonicalQso canonical(const QsoRecord &record);
    static MergeResult threeWayMerge(const CanonicalQso &base, const CanonicalQso &local,
                                     const CanonicalQso &remote);

signals:
    void stateChanged();
    void queueChanged();
    void progress(int page, int imported, int pushed, int conflicts);
    void error(QString message);

private:
    bool ensureSchema(QString *error = nullptr) const;
    void setState(QString value);
    QsoDatabase *m_database;
    WavelogEndpoint *m_endpoint{};
    std::function<QString(const QString &)> m_credentialResolver;
    QString m_state{"Not configured"};
    bool m_closed{};
};

} // namespace shackcq::desktop
