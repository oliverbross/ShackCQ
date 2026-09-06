#pragma once

#include "shackcq/desktop/AdifService.hpp"
#include "shackcq/desktop/ClusterController.hpp"
#include "shackcq/desktop/DesktopPanadapter.hpp"
#include "shackcq/desktop/DesktopEngagementControllers.hpp"
#include "shackcq/desktop/DesktopParityPlatform.hpp"
#include "shackcq/desktop/DesktopPlatform.hpp"
#include "shackcq/desktop/DesktopRadioController.hpp"
#include "shackcq/desktop/DesktopRotatorController.hpp"
#include "shackcq/desktop/RfObservationModel.hpp"
#include "shackcq/desktop/RemoteStationService.hpp"
#include "shackcq/desktop/RemoteStationClient.hpp"
#include "shackcq/desktop/WavelogSync.hpp"

#include <QQmlApplicationEngine>
#include <QTemporaryDir>
#include <QUrl>

namespace shackcq::desktop {

class DesktopApplication final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString currentDestination READ currentDestination WRITE
                 setCurrentDestination NOTIFY currentDestinationChanged)
  Q_PROPERTY(bool shuttingDown READ shuttingDown NOTIFY shuttingDownChanged)
  Q_PROPERTY(bool demoMode READ demoMode CONSTANT)
  Q_PROPERTY(
      int galleryVariant READ galleryVariant NOTIFY galleryVariantChanged)
  Q_PROPERTY(QVariantList commands READ commands NOTIFY commandStateChanged)
  Q_PROPERTY(bool editLayoutMode READ editLayoutMode WRITE setEditLayoutMode
                 NOTIFY editLayoutModeChanged)
  Q_PROPERTY(bool sidebarCollapsed READ sidebarCollapsed WRITE
                 setSidebarCollapsed NOTIFY sidebarCollapsedChanged)
public:
  explicit DesktopApplication(QObject *parent = nullptr);
  ~DesktopApplication() override;
  bool initialize(QString *error = nullptr);
  void expose(QQmlApplicationEngine &engine);
  QString currentDestination() const { return m_currentDestination; }
  void setCurrentDestination(const QString &destination);
  void setGalleryVariant(const QString &workspace, int variant);
  bool prepareGalleryTci(const QUrl &endpoint);
  bool shuttingDown() const { return m_shuttingDown; }
  bool demoMode() const { return m_demoMode; }
  int galleryVariant() const { return m_galleryVariant; }
  bool editLayoutMode() const { return m_editLayoutMode; }
  void setEditLayoutMode(bool enabled);
  bool sidebarCollapsed() const { return m_sidebarCollapsed; }
  void setSidebarCollapsed(bool collapsed);
  QVariantList commands() const;
  Q_INVOKABLE QVariantMap health() const;
  Q_INVOKABLE QVariantMap intelligence() const;
  Q_INVOKABLE QVariantMap buildInformation() const;
  Q_INVOKABLE bool saveFastEntry(const QVariantMap &values);
  Q_INVOKABLE QString localFilePath(const QUrl &url) const;
  Q_INVOKABLE QVariantMap panelGeometry(const QString &workspace,
                                        const QString &panel,
                                        const QVariantMap &fallback) const;
  Q_INVOKABLE void savePanelGeometry(const QString &workspace,
                                     const QString &panel,
                                     const QVariantMap &geometry);
  Q_INVOKABLE void resetWorkspaceLayout(const QString &workspace);
  Q_INVOKABLE void invokeCommand(const QString &commandId);
  Q_INVOKABLE void globalStop();
  Q_INVOKABLE void shutdown();

signals:
  void currentDestinationChanged();
  void shuttingDownChanged();
  void galleryVariantChanged();
  void editLayoutModeChanged();
  void sidebarCollapsedChanged();
  void commandStateChanged();
  void workspaceLayoutReset(QString workspace);
  void commandInvoked(QString commandId);
  void quitRequested();
  void error(QString message);

private:
  DesktopPaths m_paths;
  SystemCredentialVault m_credentials;
  std::unique_ptr<DesktopConfigurationManager> m_configuration;
  std::unique_ptr<QsoDatabase> m_database;
  std::unique_ptr<QsoTableModel> m_logbook;
  std::unique_ptr<AdifService> m_adif;
  SpotRepository m_spots;
  RfObservationModel m_rfObservations;
  ClusterController m_cluster;
  WavelogSyncEngine *m_wavelog{};
  HamlibModelRegistry m_radioModels;
  DesktopRadioController m_radio;
  DesktopRotatorController m_rotator;
  DesktopPanadapter m_panadapter;
  RemoteStationService m_remote;
  RemoteStationClient m_remoteClient;
  DesktopParityPlatform m_parity;
  DesktopKeyerController m_keyer;
  DesktopNotificationController m_notifications;
  SupportBundle m_supportBundle;
  std::unique_ptr<QTemporaryDir> m_demoDirectory;
  QString m_currentDestination{"Home"};
  bool m_shuttingDown{};
  bool m_demoMode{};
  bool m_editLayoutMode{};
  bool m_sidebarCollapsed{};
  int m_galleryVariant{-1};
};

} // namespace shackcq::desktop
