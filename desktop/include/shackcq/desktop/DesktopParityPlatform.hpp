#pragma once

#include <QAbstractListModel>
#include <QHash>
#include <QNetworkAccessManager>
#include <QTcpSocket>
#include <QUdpSocket>
#include <QObject>
#include <QPointer>
#include <QSqlDatabase>
#include <atomic>
#include <functional>
#include <QVariantMap>
#include <QVector>
#include <utility>

class QNetworkReply;

namespace shackcq::desktop {

class ProviderResponsePolicy final {
public:
    static QVariantMap evaluate(int status, const QString &contentType, const QByteArray &body,
                                const QStringList &acceptedContentTypes, int maximumBytes,
                                bool hasCache, const QString &networkError = {},
                                const QByteArray &retryAfter = {});
};

class MapListModel final : public QAbstractListModel {
    Q_OBJECT
    Q_PROPERTY(int count READ rowCount NOTIFY countChanged)
public:
    enum Role {
        ItemRole = Qt::UserRole + 1,
        KeyRole,
        TitleRole,
        SubtitleRole,
        StateRole,
        DetailRole,
        CategoryRole,
        ValueRole,
        TimestampRole,
        EnabledRole
    };

    explicit MapListModel(QObject *parent = nullptr);
    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;
    Q_INVOKABLE QVariantMap item(int row) const;
    void replace(QVariantList rows);
    void update(const QString &key, const QVariantMap &values);

signals:
    void countChanged();

private:
    QVariantList m_rows;
};

class DesktopParityPlatform final : public QObject {
    Q_OBJECT
    Q_PROPERTY(MapListModel *homeModules READ homeModules CONSTANT)
    Q_PROPERTY(MapListModel *providers READ providers CONSTANT)
    Q_PROPERTY(MapListModel *digiModes READ digiModes CONSTANT)
    Q_PROPERTY(MapListModel *neuralOpportunities READ neuralOpportunities CONSTANT)
    Q_PROPERTY(MapListModel *contestDefinitions READ contestDefinitions CONSTANT)
    Q_PROPERTY(MapListModel *contestLog READ contestLog CONSTANT)
    Q_PROPERTY(MapListModel *groupsMessages READ groupsMessages CONSTANT)
    Q_PROPERTY(MapListModel *portableActivity READ portableActivity CONSTANT)
    Q_PROPERTY(MapListModel *satellitePasses READ satellitePasses CONSTANT)
    Q_PROPERTY(MapListModel *keyerMacros READ keyerMacros CONSTANT)
    Q_PROPERTY(MapListModel *closureLedger READ closureLedger CONSTANT)
    Q_PROPERTY(MapListModel *nativeRadioProfiles READ nativeRadioProfiles CONSTANT)
    Q_PROPERTY(MapListModel *nativeRotatorProtocols READ nativeRotatorProtocols CONSTANT)
    Q_PROPERTY(MapListModel *presets READ presets CONSTANT)
    Q_PROPERTY(MapListModel *eqBands READ eqBands CONSTANT)
    Q_PROPERTY(MapListModel *digiDecodes READ digiDecodes CONSTANT)
    Q_PROPERTY(MapListModel *bandMapRows READ bandMapRows CONSTANT)
    Q_PROPERTY(MapListModel *dxWorkspaceRows READ dxWorkspaceRows CONSTANT)
    Q_PROPERTY(MapListModel *intelligenceRows READ intelligenceRows CONSTANT)
    Q_PROPERTY(MapListModel *operationsRows READ operationsRows CONSTANT)
    Q_PROPERTY(MapListModel *alerts READ alerts CONSTANT)
    Q_PROPERTY(MapListModel *groupsOutbox READ groupsOutbox CONSTANT)
    Q_PROPERTY(MapListModel *groupsMemberships READ groupsMemberships CONSTANT)
    Q_PROPERTY(MapListModel *groupsTopics READ groupsTopics CONSTANT)
    Q_PROPERTY(MapListModel *ownerHealth READ ownerHealth CONSTANT)
    Q_PROPERTY(QString activeReview READ activeReview NOTIFY activeReviewChanged)
    Q_PROPERTY(QString safetyState READ safetyState NOTIFY safetyStateChanged)
    Q_PROPERTY(QString digiState READ digiState NOTIFY workflowStateChanged)
    Q_PROPERTY(QString contestState READ contestState NOTIFY workflowStateChanged)
    Q_PROPERTY(QString n1mmState READ n1mmState NOTIFY workflowStateChanged)
    Q_PROPERTY(QString scpState READ scpState NOTIFY workflowStateChanged)
    Q_PROPERTY(QString groupsCredentialAlias READ groupsCredentialAlias NOTIFY groupsConfigurationChanged)
    Q_PROPERTY(QVariantMap operatingContext READ operatingContext NOTIFY operatingContextChanged)
    Q_PROPERTY(bool demoMode READ demoMode CONSTANT)
    Q_PROPERTY(int galleryBandMapLayout READ galleryBandMapLayout WRITE setGalleryBandMapLayout NOTIFY galleryBandMapLayoutChanged)
public:
    explicit DesktopParityPlatform(QObject *parent = nullptr);
    ~DesktopParityPlatform() override;

    bool open(const QString &databaseDirectory, const QString &cacheDirectory,
              bool demoMode, QString *error = nullptr);
    void close();

    MapListModel *homeModules() { return &m_homeModules; }
    MapListModel *providers() { return &m_providers; }
    const MapListModel *providers() const { return &m_providers; }
    MapListModel *digiModes() { return &m_digiModes; }
    MapListModel *neuralOpportunities() { return &m_neuralOpportunities; }
    MapListModel *contestDefinitions() { return &m_contestDefinitions; }
    MapListModel *contestLog() { return &m_contestLog; }
    MapListModel *groupsMessages() { return &m_groupsMessages; }
    MapListModel *portableActivity() { return &m_portableActivity; }
    MapListModel *satellitePasses() { return &m_satellitePasses; }
    MapListModel *keyerMacros() { return &m_keyerMacros; }
    MapListModel *closureLedger() { return &m_closureLedger; }
    MapListModel *nativeRadioProfiles() { return &m_nativeRadioProfiles; }
    MapListModel *nativeRotatorProtocols() { return &m_nativeRotatorProtocols; }
    MapListModel *presets() { return &m_presets; }
    MapListModel *eqBands() { return &m_eqBands; }
    MapListModel *digiDecodes() { return &m_digiDecodes; }
    MapListModel *bandMapRows() { return &m_bandMapRows; }
    MapListModel *dxWorkspaceRows() { return &m_dxWorkspaceRows; }
    MapListModel *intelligenceRows() { return &m_intelligenceRows; }
    MapListModel *operationsRows() { return &m_operationsRows; }
    MapListModel *alerts() { return &m_alerts; }
    MapListModel *groupsOutbox() { return &m_groupsOutbox; }
    MapListModel *groupsMemberships() { return &m_groupsMemberships; }
    MapListModel *groupsTopics() { return &m_groupsTopics; }
    MapListModel *ownerHealth() { return &m_ownerHealth; }
    QString activeReview() const { return m_activeReview; }
    QString safetyState() const { return m_safetyState; }
    QString digiState() const { return m_digiState; }
    QString contestState() const { return m_contestState; }
    QString n1mmState() const { return m_n1mmState; }
    QString scpState() const { return m_scpState; }
    QString groupsCredentialAlias() const { return m_groupsCredentialAlias; }
    QVariantMap operatingContext() const { return m_operatingContext; }
    bool demoMode() const { return m_demoMode; }
    int galleryBandMapLayout() const { return m_galleryBandMapLayout; }
    void setGalleryBandMapLayout(int value);

    Q_INVOKABLE QVariantMap workspaceSummary(const QString &workspace) const;
    Q_INVOKABLE QVariantMap databaseHealth() const;
    Q_INVOKABLE bool setHomeModuleVisible(const QString &key, bool visible);
    Q_INVOKABLE bool setProviderEnabled(const QString &key, bool enabled);
    Q_INVOKABLE bool refreshProvider(const QString &key);
    Q_INVOKABLE bool prepareReceiveReview(const QString &source, const QVariantMap &target);
    Q_INVOKABLE bool prepareContestMerge(const QVariantMap &session);
    Q_INVOKABLE bool prepareGroupsDraft(const QVariantMap &draft);
    Q_INVOKABLE bool selectSatellitePass(const QVariantMap &pass);
    Q_INVOKABLE QVariantMap closureStatus(const QString &foundation) const;
    Q_INVOKABLE QVariantMap closureSummary() const;
    Q_INVOKABLE bool updateOperatingContext(const QVariantMap &context);
    Q_INVOKABLE QVariantMap nativeRadioFrame(const QString &profileId,
                                              const QString &operation,
                                              const QVariant &value = {}) const;
    Q_INVOKABLE QVariantMap nativeRotatorFrame(const QString &protocol,
                                                const QString &operation,
                                                double azimuth = 0,
                                                double elevation = 0) const;
    Q_INVOKABLE bool savePreset(const QVariantMap &preset);
    Q_INVOKABLE bool removePreset(const QString &presetId);
    Q_INVOKABLE bool reviewPresetRecall(const QString &presetId);
    Q_INVOKABLE bool saveEqDraft(const QVariantList &rxBands,
                                  const QVariantList &txBands);
    Q_INVOKABLE bool reviewEqApply();
    Q_INVOKABLE bool startDigiReceive(const QString &mode,
                                      const QString &audioRouteId,
                                      int sampleRate);
    Q_INVOKABLE void stopDigi();
    Q_INVOKABLE bool ingestLocalDecode(const QVariantMap &decode);
    Q_INVOKABLE bool startDxChaser(const QVariantMap &candidate, bool dryRun);
    Q_INVOKABLE bool startContest(const QString &definitionId,
                                  const QString &stationProfileId);
    Q_INVOKABLE bool stageContestQso(const QVariantMap &qso);
    Q_INVOKABLE QVariantMap contestScore() const;
    Q_INVOKABLE QVariantMap parseN1mmPacket(const QByteArray &packet) const;
    Q_INVOKABLE bool registerN1mmPeer(const QString &peerId, const QString &endpoint);
    Q_INVOKABLE bool setN1mmPeerTrusted(const QString &peerId, bool trusted);
    Q_INVOKABLE bool updateN1mmPeerLifecycle(const QString &peerId, const QString &event);
    Q_INVOKABLE bool ingestN1mmPacket(const QString &peerId, const QByteArray &packet);
    Q_INVOKABLE bool startN1mmDiscovery(quint16 port = 12060);
    Q_INVOKABLE bool connectN1mmPeer(const QString &peerId);
    Q_INVOKABLE void stopN1mmRuntime();
    Q_INVOKABLE QByteArray frameN1mmTcpPacket(const QByteArray &packet) const;
    Q_INVOKABLE QVariantList parseN1mmTcpFrames(const QByteArray &frames) const;
    Q_INVOKABLE bool refreshScp();
    Q_INVOKABLE QVariantMap scpStatus() const;
    Q_INVOKABLE QVariantMap scpLookup(const QString &partial, int limit = 12) const;
    Q_INVOKABLE QVariantMap computeEmpiricalOutlook(const QVariantList &evidence,
                                                     int windowMinutes) const;
    Q_INVOKABLE QVariantList evaluateBandMap(const QVariantList &spots) const;
    Q_INVOKABLE bool refreshSpotProjections(const QVariantList &spots);
    Q_INVOKABLE QVariantMap predictSatellitePasses(const QString &name,
                                                    const QString &line1,
                                                    const QString &line2,
                                                    double latitude,
                                                    double longitude,
                                                    double altitudeKm,
                                                    qint64 startUtc,
                                                    qint64 endUtc) const;
    Q_INVOKABLE bool calculateSatellitePasses(const QString &name,
                                              const QString &line1,
                                              const QString &line2,
                                              double latitude,
                                              double longitude,
                                              double altitudeKm,
                                              qint64 startUtc,
                                              qint64 endUtc);
    Q_INVOKABLE bool queueGroupsDraft(const QVariantMap &draft);
    Q_INVOKABLE bool setGroupsCredentialAlias(const QString &alias);
    Q_INVOKABLE bool refreshGroupsMemberships();
    Q_INVOKABLE bool refreshGroupsTopics(const QString &groupId);
    Q_INVOKABLE bool refreshGroupsMessages(const QString &groupId,
                                           const QString &topicId);
    Q_INVOKABLE bool sendGroupsOutbox(const QString &outboxId);
    Q_INVOKABLE bool reconcileGroupsDelivery(const QString &outboxId,
                                              const QString &state,
                                              const QString &serverId = {});
    Q_INVOKABLE bool injectTestAlert(const QString &profile,
                                     const QString &title,
                                     const QString &body);
    Q_INVOKABLE void clearReview();
    Q_INVOKABLE void globalStop();
    QVariantMap runDeterministicScaleProbe(QString *error = nullptr);
    QVariantList decodeDigiSlotForTest(const QString &mode,
                                       const QVector<float> &samples,
                                       quint32 sampleRate,
                                       QString *error = nullptr) const;
    void feedDigiAudio(const QString &audioRouteId, quint32 sampleRate,
                       const QVector<float> &samples);
    void setCredentialResolver(std::function<QString(const QString &)> resolver);
    QVariantMap groupsConfiguration() const;
    bool restoreGroupsConfiguration(const QVariantMap &section,
                                    QString *error = nullptr);
    void setGroupsEndpointForTest(const QUrl &endpoint);
    void setScpEndpointForTest(const QUrl &endpoint);
    bool importScpPayloadForTest(const QByteArray &payload, const QUrl &source,
                                 qint64 sourceDate, QString *error = nullptr);
    quint16 n1mmDiscoveryPortForTest() const { return m_n1mmUdp.localPort(); }

signals:
    void activeReviewChanged();
    void safetyStateChanged();
    void providerError(QString provider, QString message);
    void galleryBandMapLayoutChanged();
    void workflowStateChanged();
    void operatingContextChanged();
    void notificationRequested(QString title, QString body, bool critical);
    void groupsConfigurationChanged();

private:
    struct ProviderSpec {
        ProviderSpec() = default;
        ProviderSpec(QString providerKey, QString providerTitle, QUrl providerUrl,
                     QStringList acceptedContentTypes, int byteLimit, int cooldown)
            : key(std::move(providerKey)), title(std::move(providerTitle)),
              url(std::move(providerUrl)), contentTypes(std::move(acceptedContentTypes)),
              maximumBytes(byteLimit), cooldownSeconds(cooldown) {}
        QString key;
        QString title;
        QUrl url;
        QStringList contentTypes;
        int maximumBytes{};
        int cooldownSeconds{};
        bool enabled{};
        qint64 lastAttempt{};
        QPointer<QNetworkReply> reply;
        QByteArray body;
    };
    struct StoreSpec {
        QString key;
        QString path;
        int schema{};
        QString connection;
        QSqlDatabase database;
    };

    bool openStore(StoreSpec &store, QString *error);
    bool migrateStore(StoreSpec &store, QString *error);
    void loadRegistries();
    void seedDemo();
    bool loadFunctionalOwners(QString *error);
    void functionalStop();
    void refreshOwnerHealth();
    bool beginGroupsRequest(const QString &phase, const QString &path,
                            const QVariantMap &query = {},
                            const QVariantMap &form = {},
                            const QString &outboxId = {});
    void finishGroupsRequest();
    bool importScpPayload(const QByteArray &payload, const QUrl &source,
                          qint64 sourceDate, QString *error);
    void finishScpRequest();
    StoreSpec *store(const QString &key);
    const StoreSpec *store(const QString &key) const;
    void finishProvider(const QString &key);
    void setReview(const QString &text);
    static QString sanitizedNetworkError(const QString &message);

    MapListModel m_homeModules;
    MapListModel m_providers;
    MapListModel m_digiModes;
    MapListModel m_neuralOpportunities;
    MapListModel m_contestDefinitions;
    MapListModel m_contestLog;
    MapListModel m_groupsMessages;
    MapListModel m_portableActivity;
    MapListModel m_satellitePasses;
    MapListModel m_keyerMacros;
    MapListModel m_closureLedger;
    MapListModel m_nativeRadioProfiles;
    MapListModel m_nativeRotatorProtocols;
    MapListModel m_presets;
    MapListModel m_eqBands;
    MapListModel m_digiDecodes;
    MapListModel m_bandMapRows;
    MapListModel m_dxWorkspaceRows;
    MapListModel m_intelligenceRows;
    MapListModel m_operationsRows;
    MapListModel m_alerts;
    MapListModel m_groupsOutbox;
    MapListModel m_groupsMemberships;
    MapListModel m_groupsTopics;
    MapListModel m_ownerHealth;
    QNetworkAccessManager m_network;
    QHash<QString, ProviderSpec> m_providerSpecs;
    QVector<StoreSpec> m_stores;
    QString m_cacheDirectory;
    QString m_activeReview;
    QString m_safetyState{"Disconnected / RX only / automation disarmed"};
    QString m_digiState{"STOPPED / no audio route / TX locked"};
    QString m_contestState{"INACTIVE"};
    QString m_n1mmState{"DISABLED / loopback / untrusted / unarmed"};
    QString m_scpState{"EMPTY / runtime download required"};
    QVariantMap m_operatingContext{{"generation", 0}, {"radioConnected", false},
                                   {"transmitAccepted", false},
                                   {"rotatorMovementAccepted", false}};
    QVariantList m_eqRxDraft;
    QVariantList m_eqTxDraft;
    QString m_activeContestSession;
    QString m_activeDigiMode;
    QString m_activeAudioRoute;
    QVector<float> m_digiAudioBuffer;
    quint32 m_digiAudioSampleRate{};
    std::atomic<quint64> m_digiGeneration{1};
    std::function<QString(const QString &)> m_credentialResolver;
    QString m_groupsCredentialAlias;
    QUrl m_groupsEndpoint{"https://groups.io/api/v1"};
    QPointer<QNetworkReply> m_groupsReply;
    QByteArray m_groupsBody;
    QString m_groupsPhase;
    QString m_groupsScopeId;
    QString m_groupsOutboxId;
    qint64 m_groupsRemoteDraftId{};
    QUdpSocket m_n1mmUdp;
    QTcpSocket m_n1mmTcp;
    QByteArray m_n1mmTcpBuffer;
    QString m_n1mmTcpPeerId;
    QUrl m_scpEndpoint{"https://supercheckpartial.com/MASTER.SCP"};
    QPointer<QNetworkReply> m_scpReply;
    QByteArray m_scpBody;
    bool m_demoMode{};
    bool m_closed{true};
    int m_galleryBandMapLayout{};
};

} // namespace shackcq::desktop
