#pragma once

#include "shackcq/desktop/QsoDatabase.hpp"

#include <atomic>
#include <optional>

namespace shackcq::desktop {

class AdifService final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
public:
    explicit AdifService(QsoDatabase *database, QObject *parent = nullptr);
    bool busy() const { return m_busy; }
    Q_INVOKABLE bool importFile(const QString &path);
    Q_INVOKABLE bool exportFile(const QString &path, const QString &stationProfileId = {});
    Q_INVOKABLE void cancel();
    static QByteArray serialize(const QsoRecord &record);
    static std::optional<QsoRecord> parseRecord(const QByteArray &record, QString *error = nullptr);

signals:
    void busyChanged();
    void progress(qint64 completed, qint64 total);
    void finished(bool success, QString message);

private:
    QsoDatabase *m_database;
    std::atomic_bool m_cancelled{false};
    bool m_busy{};
};

} // namespace shackcq::desktop
