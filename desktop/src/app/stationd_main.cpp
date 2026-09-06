// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/DesktopPanadapter.hpp"
#include "shackcq/desktop/DesktopPlatform.hpp"
#include "shackcq/desktop/DesktopRadioController.hpp"
#include "shackcq/desktop/DesktopRotatorController.hpp"
#include "shackcq/desktop/RemoteStationService.hpp"

#include <QCommandLineParser>
#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLocalServer>
#include <QLocalSocket>
#include <QTextStream>
#include <QSysInfo>
#include <QUrl>

using namespace shackcq::desktop;
namespace {
const QString AdminSocket = QStringLiteral("shackcq-stationd-v1");

QJsonObject adminRequest(const QCommandLineParser &parser) {
  if (parser.isSet("status")) return {{"action", "status"}};
  if (parser.isSet("list-clients")) return {{"action", "list-clients"}};
  if (parser.isSet("pairing-offer")) return {{"action", "pairing-offer"}};
  if (parser.isSet("revoke")) return {{"action", "revoke"}, {"deviceId", parser.value("revoke")}};
  if (parser.isSet("stop")) return {{"action", "stop"}};
  return {};
}

int sendAdminRequest(const QJsonObject &request) {
  QLocalSocket socket;
  socket.connectToServer(AdminSocket, QIODevice::ReadWrite);
  if (!socket.waitForConnected(2'000)) return 5;
  socket.write(QJsonDocument(request).toJson(QJsonDocument::Compact) + '\n');
  if (!socket.waitForBytesWritten(2'000) || !socket.waitForReadyRead(3'000)) return 6;
  QTextStream(stdout) << socket.readAll();
  return 0;
}
}

int main(int argc, char **argv) {
  QCoreApplication application(argc, argv);
  QCoreApplication::setApplicationName("shackcq-stationd");
  QCoreApplication::setApplicationVersion("1.0");
  QCommandLineParser parser;
  parser.setApplicationDescription("ShackCQ Remote Station Service v1");
  parser.addHelpOption(); parser.addVersionOption();
  parser.addOption(QCommandLineOption({"f", "foreground"}, "Run the explicitly enabled service in the foreground"));
  parser.addOption(QCommandLineOption({"s", "status"}, "Print bounded service status"));
  parser.addOption(QCommandLineOption({"p", "pairing-offer"}, "Create a short-lived pairing offer"));
  parser.addOption(QCommandLineOption(QStringLiteral("list-clients"), "List paired public device metadata"));
  parser.addOption(QCommandLineOption(QStringLiteral("revoke"), "Revoke a paired device", "device-id"));
  parser.addOption(QCommandLineOption(QStringLiteral("stop"), "Request station Global Stop and shut down"));
  parser.addOption(QCommandLineOption(QStringLiteral("pair-with-shackcq"), "Exchange a one-time ShackCQ Cloud pairing code", "code"));
  parser.addOption(QCommandLineOption(QStringLiteral("cloud-origin"), "ShackCQ Cloud HTTPS origin", "origin", "https://shackcq.com"));
  parser.addOption(QCommandLineOption(QStringLiteral("agent-name"), "Public name for this Agent", "name", QSysInfo::machineHostName()));
  parser.process(application);

  const QJsonObject requestedAdminAction = adminRequest(parser);
  if (!parser.isSet("foreground") && !requestedAdminAction.isEmpty()) return sendAdminRequest(requestedAdminAction);
  if (!parser.isSet("foreground") && !parser.isSet("pair-with-shackcq")) parser.showHelp(1);

  DesktopPaths paths;
  QString error;
  if (!paths.create(&error)) { QTextStream(stderr) << error << '\n'; return 2; }
  DesktopConfigurationManager configuration(paths.configuration() + "/desktop-config.json");
  if (!configuration.load(&error)) { QTextStream(stderr) << error << '\n'; return 2; }
  SystemCredentialVault vault;
  DesktopRadioController radio;
  CloudAgentClient cloudAgent(&vault, &radio);
  DesktopRotatorController rotator;
  DesktopPanadapter panadapter;
  if (!radio.restoreConfiguration(configuration.section("radioProfiles"), &error) ||
      !rotator.restoreConfiguration(configuration.section("rotatorProfiles"), &error) ||
      !panadapter.restoreConfiguration(configuration.section("panadapter"), &error)) {
    QTextStream(stderr) << error << '\n'; return 2;
  }
  if (!cloudAgent.restoreConfiguration(configuration.section("cloudAgent"), &error)) {
    QTextStream(stderr) << error << '\n'; return 2;
  }
  if (parser.isSet("pair-with-shackcq")) {
    if (!cloudAgent.pair(QUrl(parser.value("cloud-origin")),
                         parser.value("pair-with-shackcq"),
                         parser.value("agent-name"), &error)) {
      QTextStream(stderr) << error << '\n';
      return 7;
    }
    configuration.setSection("cloudAgent", cloudAgent.configuration());
    if (!configuration.save(&error)) {
      QTextStream(stderr) << error << '\n';
      return 2;
    }
    QTextStream(stdout) << "ShackCQ Cloud Agent paired; credential stored in the operating-system vault\n";
    return 0;
  }
  RemoteStationService service(&vault, &radio, &rotator, &panadapter);
  if (!service.restoreConfiguration(configuration.section("remoteStation"), &error)) {
    QTextStream(stderr) << error << '\n'; return 2;
  }
  QObject::connect(&service, &RemoteStationService::pairingChanged, &application, [&] {
    configuration.setSection("remoteStation", service.configuration()); configuration.save();
  });
  QObject::connect(&application, &QCoreApplication::aboutToQuit, &application, [&] {
    cloudAgent.stop();
    service.globalStop(); service.stop();
    configuration.setSection("remoteStation", service.configuration()); configuration.save();
    configuration.setSection("cloudAgent", cloudAgent.configuration()); configuration.save();
  });

  QLocalServer admin;
  admin.setSocketOptions(QLocalServer::UserAccessOption);
  QLocalSocket existing;
  existing.connectToServer(AdminSocket);
  if (existing.waitForConnected(250)) {
    QTextStream(stderr) << "Another shackcq-stationd instance already owns local administration\n";
    return 4;
  }
  QLocalServer::removeServer(AdminSocket);
  if (!admin.listen(AdminSocket)) { QTextStream(stderr) << admin.errorString() << '\n'; return 4; }
  if (!service.start(&error)) { QTextStream(stderr) << error << '\n'; return 3; }
  cloudAgent.start();
  QObject::connect(&admin, &QLocalServer::newConnection, &application, [&] {
    while (QLocalSocket *socket = admin.nextPendingConnection()) {
      QObject::connect(socket, &QLocalSocket::readyRead, socket, [&, socket] {
        const QJsonDocument document = QJsonDocument::fromJson(socket->readLine(16 * 1024));
        const QJsonObject request = document.object();
        const QString action = request.value("action").toString();
        QVariant response;
        bool ok = true;
        if (action == "status") response = QVariantMap{{"remoteStation", service.health()},
                                                        {"cloudAgent", cloudAgent.health()},
                                                        {"radio", radio.health()}};
        else if (action == "list-clients") response = service.pairedDevices();
        else if (action == "pairing-offer") response = service.createPairingOffer();
        else if (action == "revoke") { service.revokeDevice(request.value("deviceId").toString()); response = QVariantMap{{"revoked", true}}; }
        else if (action == "stop") { service.globalStop(); response = QVariantMap{{"stopped", true}}; }
        else { ok = false; response = QVariantMap{{"error", "unknown admin action"}}; }
        socket->write(QJsonDocument(QJsonObject{{"ok", ok}, {"result", QJsonValue::fromVariant(response)}}).toJson(QJsonDocument::Indented));
        socket->flush(); socket->disconnectFromServer();
        if (action == "stop") QMetaObject::invokeMethod(&application, "quit", Qt::QueuedConnection);
      });
      QObject::connect(socket, &QLocalSocket::disconnected, socket, &QObject::deleteLater);
    }
  });
  if (parser.isSet("pairing-offer"))
    QTextStream(stdout) << QJsonDocument::fromVariant(service.createPairingOffer()).toJson(QJsonDocument::Indented);
  return application.exec();
}
#include "shackcq/desktop/CloudAgentClient.hpp"
