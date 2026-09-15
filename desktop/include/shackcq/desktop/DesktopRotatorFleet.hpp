// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include "shackcq/desktop/DesktopRotatorController.hpp"

#include <QElapsedTimer>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QVariantList>

namespace shackcq::desktop {

class DesktopRotatorFleet final : public QObject {
  Q_OBJECT
public:
  explicit DesktopRotatorFleet(QObject *parent = nullptr);
  ~DesktopRotatorFleet() override;

  bool restoreConfiguration(const QVariantMap &section, QString *error = nullptr);
  QVariantMap configuration() const;
  QVariantList descriptors() const;
  QJsonObject processCommand(const QJsonObject &frame, const QString &agentId,
                             quint64 agentGeneration);
  bool connectProfile(const QString &id, QString *error = nullptr);
  void disconnectProfile(const QString &id);
  void stopAll();
  QJsonArray snapshots(const QString &agentId, quint64 agentGeneration);
  int count() const { return m_entries.size(); }

signals:
  void snapshotChanged();

private:
  struct Entry;
  Entry *entry(const QString &id) const;
  QJsonObject snapshot(Entry *item, const QString &agentId,
                       quint64 agentGeneration);
  QHash<QString, Entry *> m_entries;
  QElapsedTimer m_clock;
};

} // namespace shackcq::desktop
