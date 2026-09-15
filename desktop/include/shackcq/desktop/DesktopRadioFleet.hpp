// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include "shackcq/desktop/DesktopRadioController.hpp"

#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QVariantList>

namespace shackcq::desktop {

class DesktopRadioFleet final : public QObject {
  Q_OBJECT
public:
  explicit DesktopRadioFleet(QObject *parent = nullptr);
  ~DesktopRadioFleet() override;
  bool restoreConfiguration(const QVariantMap &section, QString *error = nullptr);
  QVariantMap configuration() const;
  QVariantList descriptors() const;
  bool connectProfile(const QString &id, QString *error = nullptr);
  void disconnectProfile(const QString &id);
  void stopAll();
  bool contains(const QString &id) const;
  QJsonArray snapshots(const QString &agentId, quint64 generation);
  QJsonObject processCommand(const QJsonObject &frame, const QString &agentId,
                             quint64 generation);
  int count() const { return m_entries.size(); }

signals:
  void inventoryChanged();
  void snapshotChanged();

private:
  struct Entry;
  Entry *entry(const QString &id) const;
  static QJsonObject capabilities(const Entry *item);
  QHash<QString, Entry *> m_entries;
};

} // namespace shackcq::desktop
