// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include "shackcq/desktop/DesktopPlatform.hpp"

#include <QDateTime>
#include <QHash>
#include <QJsonObject>
#include <QLocalServer>
#include <QLocalSocket>
#include <QSet>
#include <functional>
#include <QObject>

namespace shackcq::desktop {

class LoggerIngestion;

class NativeAgentIngress final {
public:
  static constexpr qsizetype MaxRequestBytes = 16 * 1024;
  static constexpr qsizetype MaxPayloadBytes = 10 * 1024;
  explicit NativeAgentIngress(
      DesktopCredentialVault *vault, LoggerIngestion *logger,
      std::function<QDateTime()> clock = {},
      std::function<QByteArray()> secretSource = {});

  bool initialize(QString *error = nullptr);
  QJsonObject capability() const;
  QJsonObject handle(const QJsonObject &envelope);
  static QString signForTest(const QByteArray &secret,
                             const QJsonObject &envelope);

private:
  static QByteArray authenticatedBytes(const QJsonObject &envelope,
                                       QByteArray *payload = nullptr);
  DesktopCredentialVault *m_vault{};
  LoggerIngestion *m_logger{};
  std::function<QDateTime()> m_clock;
  std::function<QByteArray()> m_secretSource;
  QByteArray m_secret;
  QHash<QString, qint64> m_seenNonces;
};

class NativeAgentIngressServer final : public QObject {
public:
  static constexpr int MaxActiveConnections = 8;
  using Fallback = std::function<QJsonObject(const QJsonObject &)>;
  NativeAgentIngressServer(QLocalServer *server, NativeAgentIngress *ingress,
                           Fallback fallback = {}, QObject *parent = nullptr,
                           int idleTimeoutMs = 3000);

private:
  void acceptConnections();
  void readRequest(QLocalSocket *socket);
  void respond(QLocalSocket *socket, const QJsonObject &response);
  QLocalServer *m_server{};
  NativeAgentIngress *m_ingress{};
  Fallback m_fallback;
  QSet<QLocalSocket *> m_active;
  int m_idleTimeoutMs{};
};

} // namespace shackcq::desktop
