#include "shackcq/desktop/ClusterController.hpp"

#include "kx3/spot.hpp"

#include <QDateTime>

namespace shackcq::desktop {

ClusterController::ClusterController(SpotRepository *repository,QObject *parent):QObject(parent),m_repository(repository){
    connect(&m_socket,&QSslSocket::readyRead,this,&ClusterController::consume);
    connect(&m_socket,&QSslSocket::connected,this,[this]{if(m_tls)m_socket.startClientEncryption();else{m_socket.write(m_callsign.toUtf8()+"\r\n");setState("Connected");}});
    connect(&m_socket,&QSslSocket::encrypted,this,[this]{m_socket.write(m_callsign.toUtf8()+"\r\n");setState("Connected (TLS)");});
    connect(&m_socket,&QSslSocket::disconnected,this,[this]{setState("Disconnected");});
    connect(&m_socket,&QSslSocket::errorOccurred,this,[this](QAbstractSocket::SocketError){setState("Error",m_socket.errorString().left(300));});
    m_keepalive.setInterval(60000);connect(&m_keepalive,&QTimer::timeout,this,[this]{if(m_socket.state()==QAbstractSocket::ConnectedState)m_socket.write("\r\n");});
}
void ClusterController::setState(QString state,QString error){m_state=std::move(state);m_errorText=std::move(error);emit stateChanged();}
void ClusterController::setShDxCount(int count){count=qBound(1,count,500);if(count==m_shDxCount)return;m_shDxCount=count;emit shDxCountChanged();}
void ClusterController::connectProfile(const QString&host,int port,const QString&callsign,bool tls){if(host.trimmed().isEmpty()||port<1||port>65535||normalizedCallsign(callsign).isEmpty()){setState("Error","Valid host, port, and login callsign are required");return;}disconnectProfile();m_callsign=normalizedCallsign(callsign);m_tls=tls;setState("Connecting");m_socket.connectToHost(host,static_cast<quint16>(port));m_keepalive.start();}
void ClusterController::disconnectProfile(){m_keepalive.stop();m_buffer.clear();m_socket.abort();setState("Disconnected");}
void ClusterController::requestHistory(){if(m_socket.state()==QAbstractSocket::ConnectedState)m_socket.write(QStringLiteral("SH/DX %1\r\n").arg(m_shDxCount).toLatin1());}
void ClusterController::consume(){m_buffer+=m_socket.readAll();if(m_buffer.size()>65536){m_buffer.clear();setState("Error","Cluster line buffer exceeded 64 KiB");m_socket.abort();return;}while(true){const qsizetype end=m_buffer.indexOf('\n');if(end<0)break;const QByteArray line=m_buffer.left(end).trimmed();m_buffer.remove(0,end+1);ingestFixtureLine(line,QDateTime::currentSecsSinceEpoch());}}
void ClusterController::ingestFixtureLine(const QByteArray&line,qint64 receivedAt){const auto parsed=kx3::parse_cluster_spot(std::string_view(line.constData(),static_cast<std::size_t>(line.size())),receivedAt);if(!parsed)return;SpotObservation spot;spot.frequencyHz=parsed->frequency_hz;spot.callsign=QString::fromStdString(parsed->callsign);spot.spotter=QString::fromStdString(parsed->spotter);spot.comment=QString::fromStdString(parsed->comment);spot.band=QString::fromStdString(parsed->band);spot.mode=QString::fromStdString(parsed->mode);spot.source="DX Cluster";spot.receivedAt=parsed->received_epoch;m_repository->ingest(std::move(spot));}

} // namespace shackcq::desktop
