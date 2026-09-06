#pragma once

#include "shackcq/desktop/DesktopModels.hpp"

#include <QAbstractTableModel>
#include <QSqlDatabase>
#include <limits>

namespace shackcq::desktop {

struct QsoQuery {
    QString stationProfileId;
    QString callsign;
    QString band;
    QString mode;
    QString provenance;
    QString sortColumn{"created_at"};
    Qt::SortOrder sortOrder{Qt::DescendingOrder};
    qint64 cursorCreatedAt{std::numeric_limits<qint64>::max()};
    QString cursorId;
    int limit{250};
};

class QsoDatabase final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString path READ path CONSTANT)
    Q_PROPERTY(qulonglong revision READ revision NOTIFY revisionChanged)
public:
    static constexpr int SchemaVersion = 16;
    explicit QsoDatabase(QString path, QObject *parent = nullptr);
    ~QsoDatabase() override;

    bool open(QString *error = nullptr);
    QString path() const { return m_path; }
    quint64 revision() const;
    bool save(const QsoRecord &record, QString *error = nullptr);
    bool tombstone(const QString &id, QString *error = nullptr);
    QVector<QsoRecord> page(const QsoQuery &query, QString *error = nullptr) const;
    int count(const QsoQuery &query = {}, QString *error = nullptr) const;
    QVariantMap workedConfirmed(const QString &callsign, const QString &band,
                                const QString &mode) const;
    QVariantMap intelligenceSummary(const QsoQuery &query = {}) const;
    bool rebuildProjection(QString *error = nullptr);
    bool verifyProjection(QString *error = nullptr) const;
    QSqlDatabase connection() const { return m_database; }

signals:
    void revisionChanged();

private:
    bool migrate(QString *error);
    bool execute(const QString &sql, QString *error) const;
    bool updateProjection(const QsoRecord &record, QString *error);
    QString m_path;
    QString m_connectionName;
    QSqlDatabase m_database;
};

class QsoTableModel final : public QAbstractTableModel {
    Q_OBJECT
    Q_PROPERTY(int total READ total NOTIFY totalChanged)
    Q_PROPERTY(int pageSize READ pageSize WRITE setPageSize NOTIFY pageSizeChanged)
public:
    explicit QsoTableModel(QsoDatabase *database, QObject *parent = nullptr);
    int rowCount(const QModelIndex &parent = {}) const override;
    int columnCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QVariant headerData(int section, Qt::Orientation orientation, int role) const override;
    QHash<int, QByteArray> roleNames() const override;
    int total() const { return m_total; }
    int pageSize() const { return m_query.limit; }
    void setPageSize(int value);
    Q_INVOKABLE void setFilters(const QString &callsign, const QString &band,
                                const QString &mode, const QString &provenance);
    Q_INVOKABLE void reload();
    Q_INVOKABLE void nextPage();
    Q_INVOKABLE void firstPage();
    Q_INVOKABLE QVariantMap exact(int row) const;

signals:
    void totalChanged();
    void pageSizeChanged();
    void error(QString message);

private:
    QsoDatabase *m_database;
    QsoQuery m_query;
    QVector<QsoRecord> m_rows;
    int m_total{};
};

QVariantMap qsoToVariant(const QsoRecord &record);

} // namespace shackcq::desktop
