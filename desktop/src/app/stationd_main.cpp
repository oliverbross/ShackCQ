// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/CloudAgentClient.hpp"
#include "shackcq/desktop/DesktopPanadapter.hpp"
#include "shackcq/desktop/DesktopPlatform.hpp"
#include "shackcq/desktop/DesktopRadioController.hpp"
#include "shackcq/desktop/DesktopRotatorController.hpp"
#include "shackcq/desktop/RemoteStationService.hpp"

#include <QCommandLineParser>
#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QLocalServer>
#include <QLocalSocket>
#include <QTextStream>
#include <QSysInfo>
#include <QUrl>
#include <QSerialPortInfo>

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
  parser.addOption(QCommandLineOption(QStringLiteral("list-hamlib-models"), "List compiled Hamlib radio models as JSON"));
  parser.addOption(QCommandLineOption(QStringLiteral("list-serial-ports"), "List local serial routes as JSON"));
  parser.addOption(QCommandLineOption(QStringLiteral("configure-hamlib"), "Persist a Hamlib model id for safe startup autoconnect", "model-id"));
  parser.addOption(QCommandLineOption(QStringLiteral("radio-route"), "Explicit local serial/network route used with --configure-hamlib", "route"));
  parser.addOption(QCommandLineOption(QStringLiteral("radio-baud"), "Serial speed used with --configure-hamlib", "baud", "38400"));
  parser.addOption(QCommandLineOption(QStringLiteral("no-radio-autoconnect"), "Persist the Hamlib profile without startup autoconnect"));
  parser.addOption(QCommandLineOption(QStringLiteral("test-radio-connection"), "Open/read the configured receive-only Hamlib profile once, then close"));
  parser.addOption(QCommandLineOption(QStringLiteral("clear-hamlib-profile"), "Disconnect and remove the persisted Hamlib profile"));
  parser.addOption(QCommandLineOption(QStringLiteral("unpair-shackcq"), "Remove the cloud Agent credential from the operating-system vault"));
  parser.process(application);

  const QJsonObject requestedAdminAction = adminRequest(parser);
  if (!parser.isSet("foreground") && !requestedAdminAction.isEmpty()) return sendAdminRequest(requestedAdminAction);
  const bool setupAction = parser.isSet("pair-with-shackcq") ||
      parser.isSet("list-hamlib-models") || parser.isSet("list-serial-ports") ||
      parser.isSet("configure-hamlib") || parser.isSet("clear-hamlib-profile") ||
      parser.isSet("unpair-shackcq");
  if (!parser.isSet("foreground") && !setupAction) parser.showHelp(1);

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
  if (parser.isSet("list-hamlib-models")) {
    HamlibModelRegistry registry;
    QJsonArray rows;
    for (const RadioModel &model : registry.allModels())
      rows.push_back(QJsonObject{{"id", model.id},
                                 {"manufacturer", model.manufacturer},
                                 {"model", model.model},
                                 {"backend", model.backend},
                                 {"transport", model.transport}});
    QTextStream(stdout) << QJsonDocument(rows).toJson(QJsonDocument::Indented);
    return 0;
  }
  if (parser.isSet("list-serial-ports")) {
    QJsonArray rows;
    for (const QSerialPortInfo &port : QSerialPortInfo::availablePorts())
      rows.push_back(QJsonObject{{"route", port.portName()},
                                 {"systemLocation", port.systemLocation()},
                                 {"description", port.description()}});
    QTextStream(stdout) << QJsonDocument(rows).toJson(QJsonDocument::Indented);
    return 0;
  }
  if (parser.isSet("configure-hamlib")) {
    bool modelOk = false, baudOk = false;
    const int modelId = parser.value("configure-hamlib").toInt(&modelOk);
    const int baudRate = parser.value("radio-baud").toInt(&baudOk);
    if (!modelOk || !baudOk ||
        !radio.saveHamlibProfile(modelId, parser.value("radio-route"), baudRate,
                                 !parser.isSet("no-radio-autoconnect"))) {
      QTextStream(stderr) << "Hamlib profile is invalid; use --list-hamlib-models and --list-serial-ports\n";
      return 8;
    }
    if (parser.isSet("test-radio-connection")) {
      if (!radio.connectRadio(modelId, parser.value("radio-route"), baudRate))
        return 9;
      QTextStream(stdout) << QJsonDocument::fromVariant(radio.health()).toJson(QJsonDocument::Indented);
      radio.disconnectRadio();
    }
    configuration.setSection("radioProfiles", radio.configuration());
    if (!configuration.save(&error)) { QTextStream(stderr) << error << '\n'; return 2; }
    QTextStream(stdout) << "Hamlib profile saved; receive-only startup autoconnect "
                        << (parser.isSet("no-radio-autoconnect") ? "disabled\n" : "enabled\n");
    return 0;
  }
  if (parser.isSet("clear-hamlib-profile")) {
    radio.clearHamlibProfile();
    configuration.setSection("radioProfiles", radio.configuration());
    if (!configuration.save(&error)) { QTextStream(stderr) << error << '\n'; return 2; }
    QTextStream(stdout) << "Hamlib profile removed\n";
    return 0;
  }
  if (parser.isSet("unpair-shackcq")) {
    if (!cloudAgent.unpair(&error)) { QTextStream(stderr) << error << '\n'; return 7; }
    configuration.setSection("cloudAgent", cloudAgent.configuration());
    if (!configuration.save(&error)) { QTextStream(stderr) << error << '\n'; return 2; }
    QTextStream(stdout) << "ShackCQ Cloud Agent unpaired\n";
    return 0;
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
  radio.startConfiguredAutoConnect();
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
