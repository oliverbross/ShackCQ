#pragma once

#include <QFile>
#include <QLocalServer>
#include <QObject>
#include <QSettings>
#include <QUrl>
#include <optional>

namespace shackcq::desktop {

class DesktopPaths final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString configuration READ configuration CONSTANT)
    Q_PROPERTY(QString databases READ databases CONSTANT)
    Q_PROPERTY(QString cache READ cache CONSTANT)
    Q_PROPERTY(QString logs READ logs CONSTANT)
    Q_PROPERTY(QString exports READ exports CONSTANT)
    Q_PROPERTY(QString supportBundles READ supportBundles CONSTANT)
public:
    explicit DesktopPaths(QObject *parent = nullptr);
    void setEphemeralRoot(const QString &root);
    QString configuration() const { return m_configuration; }
    QString databases() const { return m_databases; }
    QString cache() const { return m_cache; }
    QString logs() const { return m_logs; }
    QString exports() const { return m_exports; }
    QString supportBundles() const { return m_supportBundles; }
    bool create(QString *error = nullptr) const;
private:
    QString m_configuration;
    QString m_databases;
    QString m_cache;
    QString m_logs;
    QString m_exports;
    QString m_supportBundles;
};

class DesktopCredentialVault : public QObject {
    Q_OBJECT
public:
    explicit DesktopCredentialVault(QObject *parent = nullptr) : QObject(parent) {}
    virtual bool write(const QString &alias, const QString &label, const QString &secret,
                       QString *error = nullptr) = 0;
    virtual std::optional<QString> read(const QString &alias, QString *error = nullptr) const = 0;
    virtual bool remove(const QString &alias, QString *error = nullptr) = 0;
    Q_INVOKABLE bool configured(const QString &alias) const { return read(alias).has_value(); }
    Q_INVOKABLE bool store(const QString &alias, const QString &label, const QString &secret) {
        QString error;
        return write(alias, label, secret, &error);
    }
    Q_INVOKABLE bool erase(const QString &alias) {
        QString error;
        return remove(alias, &error);
    }
};

class SystemCredentialVault final : public DesktopCredentialVault {
    Q_OBJECT
public:
    using DesktopCredentialVault::DesktopCredentialVault;
    bool write(const QString &alias, const QString &label, const QString &secret,
               QString *error = nullptr) override;
    std::optional<QString> read(const QString &alias, QString *error = nullptr) const override;
    bool remove(const QString &alias, QString *error = nullptr) override;
};

class FakeCredentialVault final : public DesktopCredentialVault {
    Q_OBJECT
public:
    using DesktopCredentialVault::DesktopCredentialVault;
    bool write(const QString &alias, const QString &label, const QString &secret,
               QString *error = nullptr) override;
    std::optional<QString> read(const QString &alias, QString *error = nullptr) const override;
    bool remove(const QString &alias, QString *error = nullptr) override;
private:
    QHash<QString, QString> m_values;
};

class DesktopConfigurationManager final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString lastDestination READ lastDestination WRITE setLastDestination NOTIFY changed)
public:
    DesktopConfigurationManager(QString path, QObject *parent = nullptr);
    bool load(QString *error = nullptr);
    bool save(QString *error = nullptr) const;
    QString lastDestination() const;
    void setLastDestination(const QString &value);
    Q_INVOKABLE QVariantMap previewImport(const QString &path) const;
    Q_INVOKABLE bool applyImport(const QString &path, const QStringList &sections, QString *error = nullptr);
    Q_INVOKABLE bool exportBundle(const QString &path, QString *error = nullptr) const;
    Q_INVOKABLE QVariantMap section(const QString &name) const;
    Q_INVOKABLE void setSection(const QString &name, const QVariantMap &value);
signals:
    void changed();
private:
    static bool safeSection(const QString &name);
    QString m_path;
    QVariantMap m_root;
};

class SingleInstance final : public QObject {
    Q_OBJECT
public:
    explicit SingleInstance(QString name, QObject *parent = nullptr);
    bool acquire();
signals:
    void activationRequested();
private:
    QString m_name;
    QLocalServer m_server;
};

class BoundedLogger final {
public:
    static bool install(const QString &directory, QString *error = nullptr);
    static void shutdown();
private:
    static void handler(QtMsgType type, const QMessageLogContext &context, const QString &message);
};

class SupportBundle final : public QObject {
    Q_OBJECT
public:
    SupportBundle(DesktopPaths *paths, QObject *parent = nullptr);
    Q_INVOKABLE QString create(const QVariantMap &health, QString *error = nullptr) const;
    void close() { m_closed = true; }
private:
    DesktopPaths *m_paths;
    bool m_closed{};
};

bool openAllowlistedExternalUrl(const QUrl &url);

} // namespace shackcq::desktop
