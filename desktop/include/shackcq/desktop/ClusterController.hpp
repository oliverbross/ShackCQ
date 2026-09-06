#pragma once

#include "shackcq/desktop/DesktopModels.hpp"

#include <QSslSocket>
#include <QTimer>

namespace shackcq::desktop {

class ClusterController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString state READ state NOTIFY stateChanged)
    Q_PROPERTY(QString errorText READ errorText NOTIFY stateChanged)
    Q_PROPERTY(int shDxCount READ shDxCount WRITE setShDxCount NOTIFY shDxCountChanged)
public:
    explicit ClusterController(SpotRepository *repository, QObject *parent = nullptr);
    QString state() const { return m_state; }
    QString errorText() const { return m_errorText; }
    int shDxCount() const { return m_shDxCount; }
    void setShDxCount(int count);
    Q_INVOKABLE void connectProfile(const QString &host, int port, const QString &callsign,
                                    bool tls);
    Q_INVOKABLE void disconnectProfile();
    Q_INVOKABLE void requestHistory();
    void ingestFixtureLine(const QByteArray &line, qint64 receivedAt);

signals:
    void stateChanged();
    void shDxCountChanged();

private:
    void setState(QString state, QString error = {});
    void consume();
    SpotRepository *m_repository;
    QSslSocket m_socket;
    QTimer m_keepalive;
    QByteArray m_buffer;
    QString m_callsign;
    QString m_state{"Disconnected"};
    QString m_errorText;
    int m_shDxCount{50};
    bool m_tls{};
};

} // namespace shackcq::desktop
