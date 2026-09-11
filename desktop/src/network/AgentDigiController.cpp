// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/AgentDigiController.hpp"
#include "shackcq/desktop/DesktopRadioController.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <QAudio>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QImage>
#include <QMediaDevices>
#include <QPointer>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSet>
#include <QSaveFile>
#include <QStandardPaths>
#include <QTimeZone>
#include <QtEndian>
#include <QUuid>
#include <QtConcurrent>

#ifdef SHACKCQ_HAVE_NATIVE_DIGI
#include "shackcq_flex.h"
#endif

namespace shackcq::desktop {
namespace {
constexpr qint64 LeaseMillis = 15'000;
constexpr qint64 MaximumWaveformSamples = 24'000'000;
constexpr qint64 StopRetryMillis = 250;
constexpr int MaximumStopRetries = 3;
constexpr int MaximumRetainedSessions = 12;
constexpr qint64 MaximumRetainedBytes = 64 * 1024 * 1024;
QString audioId(const QAudioDevice &device) {
  return QString::fromLatin1(device.id().toBase64(QByteArray::Base64UrlEncoding |
                                                   QByteArray::OmitTrailingEquals));
}
int modeIndex(const QString &mode, const QString &submode) {
  if (mode == "FT8") return submode.isEmpty() ? 0 : -1;
  if (mode == "FT4") return submode.isEmpty() ? 1 : -1;
  if (mode == "FT2") return submode.isEmpty() ? 11 : -1;
  if (mode == "FST4") {
    const QHash<QString, int> values{{"15",2},{"30",3},{"60",4},{"120",5},{"300",6}};
    return values.value(submode, -1);
  }
  if (mode == "Q65") {
    const QHash<QString, int> periods{{"15A",100},{"15B",101},{"15C",102},{"15D",103},{"15E",104},
      {"30A",105},{"30B",106},{"30C",107},{"30D",108},{"30E",109},{"60A",110},{"60B",111},
      {"60C",112},{"60D",113},{"60E",114},{"120A",115},{"120B",116},{"120C",117},{"120D",118},
      {"120E",119},{"300A",120},{"300B",121},{"300C",122},{"300D",123},{"300E",124}};
    return periods.value(submode, -1);
  }
  if (mode == "MSK144") {
    const QHash<QString, int> periods{{"5",130},{"10",131},{"15",132},{"30",133}};
    return periods.value(submode, -1);
  }
  if (mode == "JT65") return submode == "A" ? 9 : submode == "B" ? 12 : submode == "C" ? 13 : -1;
  if (mode == "WSPR") return submode.isEmpty() ? 10 : -1;
  return -1;
}
qint64 periodMillis(const QString &mode, const QString &submode) {
  if (mode == "FT8") return 15'000;
  if (mode == "FT4") return 7'500;
  if (mode == "FT2") return 3'750;
  if (mode == "FST4" || mode == "Q65" || mode == "MSK144" || mode == "JT65" || mode == "WSPR") {
    QString digits;
    for (QChar ch : submode) if (ch.isDigit()) digits.append(ch);
    if (mode == "JT65" && digits.isEmpty()) return 60'000;
    if (mode == "WSPR") return 120'000;
    return std::max(1, digits.toInt()) * 1'000LL;
  }
  return 0;
}
bool boundedId(const QString &value) {
  static const QRegularExpression pattern(QStringLiteral("^[A-Za-z0-9._:-]{1,128}$"));
  return pattern.match(value).hasMatch();
}
QString ftBaseCall(QString value) {
  value=value.trimmed().toUpper();
  if(value.startsWith('<')&&value.endsWith('>'))value=value.mid(1,value.size()-2);
  const QStringList parts=value.split('/',Qt::SkipEmptyParts);
  for(const QString &part:parts)if(part.contains(QRegularExpression("[0-9]"))&&part.size()>=3)return part;
  return parts.isEmpty()?QString{}:parts.last();
}
QString ftReport(double snr) { return QStringLiteral("%1%2").arg(snr>=0?"+":"-").arg(std::clamp(qRound(std::abs(snr)),0,49),2,10,QChar('0')); }
QJsonObject parseFt(QString text) {
  text=text.trimmed().toUpper().simplified();const QStringList words=text.split(' ',Qt::SkipEmptyParts);
  if(words.size()>=3&&words[0]=="CQ")return {{"kind","GRID"},{"from",words[1]},{"to","CQ"},{"grid",words[2]},{"raw",text}};
  if(words.size()<3)return {{"kind","OTHER"},{"raw",text}};
  const QString payload=words[2];QString kind="OTHER",report;
  if(payload=="RR73")kind="RR73";else if(payload=="RRR")kind="RRR";else if(payload=="73")kind="FINAL_73";
  else if(QRegularExpression("^R[+-][0-9]{2}$").match(payload).hasMatch()){kind="R_REPORT";report=payload.mid(1);}
  else if(QRegularExpression("^[+-][0-9]{2}$").match(payload).hasMatch()){kind="REPORT";report=payload;}
  else if(QRegularExpression("^[A-R]{2}[0-9]{2}([A-X]{2})?$").match(payload).hasMatch())kind="GRID";
  return {{"kind",kind},{"from",words[1]},{"to",words[0]},{"grid",kind=="GRID"?payload:QString{}},{"report",report},{"raw",text}};
}
}

AgentDigiController::AgentDigiController(DesktopRadioController *radio, QObject *parent)
    : QObject(parent), m_radio(radio) {
  m_retainedRoot = qEnvironmentVariable("SHACKCQ_DIGI_SESSION_DIR");
  if (m_retainedRoot.isEmpty())
    m_retainedRoot = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation) + "/digi-sessions";
  loadRetainedSessions();
  stopFtSequence("Not started");
  m_monotonic.start();
  m_supervisor.setInterval(200);
  connect(&m_supervisor, &QTimer::timeout, this, &AgentDigiController::expireLease);
  m_supervisor.start();
  m_txFinish.setSingleShot(true);
  connect(&m_txFinish, &QTimer::timeout, this, &AgentDigiController::finishTransmit);
  m_scanner.setSingleShot(true);
  connect(&m_scanner, &QTimer::timeout, this, &AgentDigiController::scannerStep);
  if(m_radio)connect(m_radio,&DesktopRadioController::aboutToDisconnect,this,[this]{stop("radio owner disconnecting");},Qt::DirectConnection);
  if(m_radio)connect(m_radio,&DesktopRadioController::preferencesChanged,this,[this]{if(m_hardwareAccepted&&m_acceptedRadioIdentity!=currentAcceptanceIdentity()){m_hardwareAccepted=false;m_localTxPermitted=false;m_acceptedRadioIdentity.clear();stop("accepted radio or audio identity changed");}});
  if (m_radio) connect(m_radio, &DesktopRadioController::snapshotChanged, this, [this] {
    if (m_state != State::RxUnconfirmed && radioMutationBlocked() &&
        (!m_radio->state().startsWith("Connected") || m_radio->frequencyHz() == 0 ||
         (m_preparedFrequencyHz > 0 && (m_radio->frequencyHz()!=m_preparedFrequencyHz ||
          m_radio->mode().compare(m_preparedRadioMode,Qt::CaseInsensitive)!=0 ||
          m_radio->filterHz()!=m_preparedFilterHz)))) stop("radio context changed");
    emit snapshotChanged();
  });
  if (m_radio) connect(m_radio, &DesktopRadioController::unsafeRadioOwnershipLost,
                       this, &AgentDigiController::handleUnsafeRadioLoss,
                       Qt::DirectConnection);
}

AgentDigiController::~AgentDigiController() {
  stop("Agent Digi owner closed");
  finishRetainedSession();
  m_dspFuture.waitForFinished();
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
  if (m_continuousContext) shackcq_digi_context_destroy(static_cast<shackcq_digi_context *>(m_continuousContext));
#endif
}

bool AgentDigiController::restoreConfiguration(const QVariantMap &section, QString *error) {
  static const QSet<QString> allowed{"schemaVersion","audioProfile","localTxPermitted","hardwareAccepted","acceptedRadioIdentity"};
  for (auto it=section.cbegin(); it!=section.cend(); ++it) if (!allowed.contains(it.key())) {
    if (error) *error="Digi configuration contains runtime or transmit state";
    return false;
  }
  if (section.value("schemaVersion",1).toInt()!=1) { if(error)*error="Unsupported Digi configuration schema"; return false; }
  const QVariantMap profile=section.value("audioProfile").toMap();
  if (!profile.isEmpty()) {
    static const QSet<QString> audioKeys{"id","inputDeviceId","outputDeviceId","sampleRate","inputChannel","outputChannel"};
    for(auto it=profile.cbegin();it!=profile.cend();++it)if(!audioKeys.contains(it.key())){if(error)*error="Invalid Digi audio profile field";return false;}
    const int rate=profile.value("sampleRate").toInt();
    const int inputChannel=profile.value("inputChannel").toInt(),outputChannel=profile.value("outputChannel").toInt();
    if(!boundedId(profile.value("id").toString())||profile.value("inputDeviceId").toString().size()>256||profile.value("outputDeviceId").toString().size()>256||rate<12'000||rate>192'000||inputChannel<0||inputChannel>31||outputChannel<0||outputChannel>31){if(error)*error="Invalid Digi audio profile";return false;}
  }
  m_profile=profile;
  m_localTxPermitted=section.value("localTxPermitted",false).toBool();
  m_acceptedRadioIdentity=section.value("acceptedRadioIdentity").toString();
  m_hardwareAccepted=section.value("hardwareAccepted",false).toBool()&&isKx3Profile()&&
      !m_acceptedRadioIdentity.isEmpty()&&m_acceptedRadioIdentity==currentAcceptanceIdentity();
  if(!m_hardwareAccepted){m_localTxPermitted=false;m_acceptedRadioIdentity.clear();}
  if(m_profile.isEmpty()){m_audioState="NOT_SELECTED";m_audioDetail="No local audio profile configured";}
  else {m_audioState="STOPPED";m_audioDetail="Explicit local audio profile ready";}
  return true;
}

QVariantMap AgentDigiController::configuration() const {
  return {{"schemaVersion",1},{"audioProfile",m_profile},{"localTxPermitted",m_localTxPermitted},
          {"hardwareAccepted",m_hardwareAccepted},{"acceptedRadioIdentity",m_hardwareAccepted?m_acceptedRadioIdentity:QString{}}};
}

bool AgentDigiController::isKx3Profile() const {
  if(!m_radio)return false;
  const int id=m_radio->hamlibProfile().value("modelId").toInt();
  HamlibModelRegistry registry;
  for(const auto &model:registry.allModels())if(model.id==id)return model.manufacturer.contains("Elecraft",Qt::CaseInsensitive)&&model.model.contains("KX3",Qt::CaseInsensitive);
  return false;
}

QString AgentDigiController::currentAcceptanceIdentity() const {
  if(!m_radio||m_profile.isEmpty())return{};
  const QVariantMap radio=m_radio->hamlibProfile();
  const QByteArray material=QStringLiteral("%1\n%2\n%3\n%4\n%5\n%6\n%7\n%8")
      .arg(radio.value("modelId").toInt()).arg(radio.value("route").toString()).arg(radio.value("baudRate").toInt())
      .arg(m_profile.value("inputDeviceId").toString()).arg(m_profile.value("outputDeviceId").toString())
      .arg(m_profile.value("sampleRate").toInt()).arg(m_profile.value("inputChannel").toInt()).arg(m_profile.value("outputChannel").toInt()).toUtf8();
  return QStringLiteral("digi-hw-v1:%1").arg(QString::fromLatin1(QCryptographicHash::hash(material,QCryptographicHash::Sha256).toHex()));
}

QJsonArray AgentDigiController::audioDevices() {
  QJsonArray rows;
  auto append=[&rows](const QAudioDevice &device,const QString &direction){const QAudioFormat f=device.preferredFormat();rows.append(QJsonObject{{"id",audioId(device)},{"description",device.description().left(160)},{"direction",direction},{"preferredSampleRate",f.sampleRate()},{"preferredChannels",f.channelCount()},{"minimumSampleRate",device.minimumSampleRate()},{"maximumSampleRate",device.maximumSampleRate()}});};
  for(const auto &device:QMediaDevices::audioInputs())append(device,"input");
  for(const auto &device:QMediaDevices::audioOutputs())append(device,"output");
  return rows;
}

void AgentDigiController::setServerTxPermitted(bool permitted) {
  if(m_serverTxPermitted==permitted)return;
  m_serverTxPermitted=permitted;
  if(!permitted&&(m_state==State::Armed||m_state==State::Ready||m_pttOwned||m_pttReleaseRequired||m_sendScheduled))stop("server Digi TX policy disabled");
  emit snapshotChanged();
}

bool AgentDigiController::radioMutationBlocked() const {
  return m_state==State::Preparing||m_state==State::Ready||m_state==State::Armed||
      m_state==State::PttConfirmed||m_state==State::Transmitting||
      m_state==State::Stopping||m_state==State::RxUnconfirmed||m_sendScheduled||
      m_pttReleaseRequired;
}

void AgentDigiController::setSafetyHooksForTest(std::function<bool(bool)> ptt,std::function<std::optional<bool>()> readback){m_pttHook=std::move(ptt);m_readbackHook=std::move(readback);}
void AgentDigiController::setLocalAcceptanceForTest(bool permitted,bool accepted){m_localTxPermitted=permitted;m_hardwareAccepted=accepted;}
void AgentDigiController::setAudioReadyForTest(){m_audioState="READY";m_audioDetail="Synthetic memory sink ready";m_lastInputMono=std::max<qint64>(1,m_monotonic.elapsed());m_state=State::RxVerified;}

QJsonObject AgentDigiController::result(const QJsonObject &frame,const QString &agentId,const QString &deviceId,quint64 generation,bool ok,const QString &code) {
  QJsonObject value{{"type","digi.command.result"},{"protocol",QJsonObject{{"major",1},{"minor",2}}},{"commandId",frame.value("commandId")},{"agentId",agentId},{"deviceId",deviceId},{"generation",QJsonValue::fromVariant(generation)},{"ok",ok},{"code",code}};
  if(m_messageRevision>0)value.insert("messageRevision",m_messageRevision);
  const QString commandId=frame.value("commandId").toString();
  if(boundedId(commandId)&&!m_commandResults.contains(commandId)){
    m_commandResults.insert(commandId,value);m_commandOrder.append(commandId);
    while(m_commandOrder.size()>256)m_commandResults.remove(m_commandOrder.takeFirst());
  }
  return value;
}

bool AgentDigiController::ownsLease(const QJsonObject &frame) const {
  return m_leaseExpiryMono>m_monotonic.elapsed()&&frame.value("browserSessionId").toString()==m_leaseBrowserSession&&frame.value("controlInstanceId").toString()==m_leaseControlInstance;
}

bool AgentDigiController::clockGood() const {
  return m_clockStartedMono>0&&m_monotonic.elapsed()-m_clockStartedMono>=30'000&&
      m_clockUtcUncertaintyMs>=0&&m_clockUtcUncertaintyMs<=100&&
      m_clockSampleUncertaintyMs>=0&&m_clockSampleUncertaintyMs<=100;
}

void AgentDigiController::resetClockEvidence(){m_clockStartedMono=m_monotonic.elapsed();m_clockStartedWall=QDateTime::currentMSecsSinceEpoch();m_clockSamples=0;m_clockUtcUncertaintyMs=-1;m_clockSampleUncertaintyMs=-1;}

void AgentDigiController::updateClockEvidence(qsizetype samples){
  if(m_clockStartedMono<=0) resetClockEvidence();
  m_clockSamples+=samples;
  const qint64 elapsed=m_monotonic.elapsed()-m_clockStartedMono;
  if(elapsed<=0) return;
  const qint64 wallElapsed=QDateTime::currentMSecsSinceEpoch()-m_clockStartedWall;
  m_clockUtcUncertaintyMs=std::llabs(wallElapsed-elapsed);
  m_clockSampleUncertaintyMs=std::llabs((m_clockSamples*1000/12'000)-elapsed);
}

void AgentDigiController::stopFtSequence(const QString &reason){
  m_ftDeadlineMono=0;m_ftAcceptedDecodes.clear();
  m_ftSequence={{"state","STOPPED"},{"role",QJsonValue::Null},{"stationCallsign",QJsonValue::Null},
    {"remoteCallsign",QJsonValue::Null},{"remoteGrid",QJsonValue::Null},{"sentReport",QJsonValue::Null},
    {"receivedReport",QJsonValue::Null},{"pendingMessage",QJsonValue::Null},{"pendingKind",QJsonValue::Null},
    {"expectedIncoming",QJsonArray{}},{"retryCount",0},{"retryLimit",3},{"autoCq",false},
    {"autoCqLimit",3},{"cqTransmissions",0},{"completionReason",reason.left(160)}};
}

bool AgentDigiController::queueFtSequenceMessage(const QString &kind,QString *error){
  const QString mine=m_ftSequence.value("stationCallsign").toString(),grid=m_ftSequence.value("stationGrid").toString().left(4),remote=m_ftSequence.value("remoteCallsign").toString();
  QString message;if(kind=="CQ")message=QString("CQ %1 %2").arg(mine,grid).trimmed();
  else if(kind=="GRID")message=QString("%1 %2 %3").arg(remote,mine,grid).trimmed();
  else if(kind=="REPORT")message=QString("%1 %2 %3").arg(remote,mine,m_ftSequence.value("sentReport").toString());
  else if(kind=="R_REPORT")message=QString("%1 %2 R%3").arg(remote,mine,m_ftSequence.value("sentReport").toString());
  else if(kind=="RR73")message=QString("%1 %2 RR73").arg(remote,mine);
  else if(kind=="FINAL_73")message=QString("%1 %2 73").arg(remote,mine);else{if(error)*error="SEQUENCE_MESSAGE_KIND_INVALID";return false;}
  const QHash<QString,QString> states{{"CQ","CQ_TX_PENDING"},{"GRID","CALL_TX_PENDING"},{"REPORT","REPORT_TX_PENDING"},{"R_REPORT","R_REPORT_TX_PENDING"},{"RR73","RR73_TX_PENDING"},{"FINAL_73","FINAL_73_TX_PENDING"}};
  m_ftSequence.insert("state",states.value(kind));m_ftSequence.insert("pendingKind",kind);m_ftSequence.insert("pendingMessage",message);m_ftSequence.insert("expectedIncoming",QJsonArray{});m_ftSequence.insert("holdReason","AWAITING_SAFETY_GATES");
  if(!prepare({{"message",message},{"maxRepeats",1}},error))return false;
  const auto ptt=pttReadback();const bool fresh=m_lastInputMono>0&&m_monotonic.elapsed()-m_lastInputMono<=2'000;
  if(m_serverTxPermitted&&m_localTxPermitted&&m_hardwareAccepted&&clockGood()&&fresh&&ptt&&!*ptt){m_armExpiryMono=m_monotonic.elapsed()+periodMillis(m_mode,m_submode)*2+2'000;m_state=State::Armed;m_repeatsRemaining=1;QString scheduleError;if(scheduleTransmit(&scheduleError)){m_ftSequence.insert("holdReason",QJsonValue::Null);return true;}m_state=State::Ready;m_ftSequence.insert("holdReason",scheduleError);}
  else if(!clockGood())m_ftSequence.insert("holdReason","CLOCK_QUALITY_UNVERIFIED");
  emit snapshotChanged();return true;
}

bool AgentDigiController::startFtSequence(const QJsonObject &p,QString *error){
  if(m_mode!="FT8"&&m_mode!="FT4"){if(error)*error="FT8_OR_FT4_REQUIRED";return false;}
  if(m_sessionId.isEmpty()||(!m_source&&m_audioState!="READY")){if(error)*error="LIVE_RX_SESSION_REQUIRED";return false;}
  const QString role=p.value("role").toString(),mine=p.value("stationCallsign").toString().trimmed().toUpper(),grid=p.value("stationGrid").toString().trimmed().toUpper();
  if(!QRegularExpression("^[A-Z0-9/]{3,16}$").match(mine).hasMatch()||!QRegularExpression("^[A-R]{2}[0-9]{2}([A-X]{2})?$").match(grid).hasMatch()){if(error)*error="STATION_IDENTITY_INVALID";return false;}
  if(role!="CQ_RUNNER"&&role!="SEARCH_AND_POUNCE"){if(error)*error="SEQUENCE_ROLE_INVALID";return false;}
  stopFtSequence("Restarted by operator");m_ftSequence={{"state","IDLE"},{"role",role},{"stationCallsign",mine},{"stationGrid",grid},{"remoteCallsign",QJsonValue::Null},{"remoteGrid",QJsonValue::Null},{"sentReport",QJsonValue::Null},{"receivedReport",QJsonValue::Null},{"pendingMessage",QJsonValue::Null},{"pendingKind",QJsonValue::Null},{"expectedIncoming",QJsonArray{}},{"retryCount",0},{"retryLimit",std::clamp(p.value("retryLimit").toInt(),0,10)},{"autoCq",p.value("autoCq").toBool()},{"autoCqLimit",std::clamp(p.value("autoCqLimit").toInt(),1,20)},{"cqTransmissions",0},{"completionReason",QJsonValue::Null},{"startedUtc",QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs)}};
  QString kind="CQ";
  if(role=="SEARCH_AND_POUNCE"){
    const QString decodeId=p.value("decodeId").toString();QJsonObject selected;for(const auto &row:m_decodes)if(row.value("id").toString()==decodeId){selected=row;break;}
    if(selected.isEmpty()||selected.value("source")!="LIVE_CAPTURE"||!selected.value("exactSlotTiming").toBool()){stopFtSequence("Selected decode is not exact live capture");if(error)*error="EXACT_LIVE_DECODE_REQUIRED";return false;}
    const QJsonObject parsed=parseFt(selected.value("text").toString());if(parsed.value("to")!="CQ"||parsed.value("from").toString().isEmpty()){stopFtSequence("Selected decode is not CQ");if(error)*error="SELECTED_CQ_REQUIRED";return false;}
    m_ftSequence.insert("remoteCallsign",parsed.value("from"));m_ftSequence.insert("remoteGrid",parsed.value("grid"));m_ftSequence.insert("sentReport",ftReport(selected.value("snr").toDouble()));kind="GRID";
  }
  return queueFtSequenceMessage(kind,error);
}

void AgentDigiController::advanceFtSequence(const QJsonObject &decode){
  const QString state=m_ftSequence.value("state").toString();if(!state.startsWith("WAIT_")||decode.value("source")!="LIVE_CAPTURE"||!decode.value("exactSlotTiming").toBool())return;
  const QString key=QString("%1|%2").arg(decode.value("slotStartMillis").toVariant().toLongLong()).arg(decode.value("text").toString().trimmed().toUpper());if(m_ftAcceptedDecodes.contains(key))return;m_ftAcceptedDecodes.insert(key);
  const QJsonObject msg=parseFt(decode.value("text").toString());const QString mine=ftBaseCall(m_ftSequence.value("stationCallsign").toString()),remote=ftBaseCall(msg.value("from").toString());if(ftBaseCall(msg.value("to").toString())!=mine||remote.isEmpty())return;
  const QString locked=ftBaseCall(m_ftSequence.value("remoteCallsign").toString()),kind=msg.value("kind").toString();QString next;
  if(state=="WAIT_CALLER"&&(kind=="GRID"||kind=="REPORT")){m_ftSequence.insert("remoteCallsign",msg.value("from"));m_ftSequence.insert("remoteGrid",msg.value("grid"));m_ftSequence.insert("sentReport",ftReport(decode.value("snr").toDouble()));if(kind=="REPORT")m_ftSequence.insert("receivedReport",msg.value("report"));next="REPORT";}
  else if(remote==locked&&state=="WAIT_REPORT"&&kind=="REPORT"){m_ftSequence.insert("receivedReport",msg.value("report"));next="R_REPORT";}
  else if(remote==locked&&state=="WAIT_R_REPORT"&&kind=="R_REPORT"){m_ftSequence.insert("receivedReport",msg.value("report"));next="RR73";}
  else if(remote==locked&&state=="WAIT_RR73"&&(kind=="RR73"||kind=="RRR"))next="FINAL_73";
  else if(remote==locked&&state=="WAIT_FINAL_73"&&kind=="FINAL_73"){m_ftSequence.insert("state","COMPLETE");m_ftSequence.insert("completionReason","Standard exchange complete");m_ftSequence.insert("expectedIncoming",QJsonArray{});m_ftDeadlineMono=0;emit snapshotChanged();return;}
  if(next.isEmpty()) return;
  m_ftSequence.insert("retryCount",0);m_ftDeadlineMono=0;QString error;if(!queueFtSequenceMessage(next,&error)){m_ftSequence.insert("state","FAILED");m_ftSequence.insert("completionReason",error);}
}

void AgentDigiController::noteFtTransmitComplete(){
  const QString kind=m_ftSequence.value("pendingKind").toString();if(kind.isEmpty())return;const QHash<QString,QString> states{{"CQ","WAIT_CALLER"},{"GRID","WAIT_REPORT"},{"REPORT","WAIT_R_REPORT"},{"R_REPORT","WAIT_RR73"},{"RR73","WAIT_FINAL_73"},{"FINAL_73","COMPLETE"}};const QString next=states.value(kind);if(next.isEmpty())return;m_ftSequence.insert("state",next);m_ftSequence.insert("pendingKind",QJsonValue::Null);m_ftSequence.insert("pendingMessage",QJsonValue::Null);if(kind=="CQ")m_ftSequence.insert("cqTransmissions",m_ftSequence.value("cqTransmissions").toInt()+1);QJsonArray expected;if(next=="WAIT_CALLER")expected={"GRID","REPORT"};else if(next=="WAIT_REPORT")expected={"REPORT"};else if(next=="WAIT_R_REPORT")expected={"R_REPORT"};else if(next=="WAIT_RR73")expected={"RRR","RR73"};else if(next=="WAIT_FINAL_73")expected={"FINAL_73"};m_ftSequence.insert("expectedIncoming",expected);m_ftSequence.insert("holdReason",QJsonValue::Null);m_ftDeadlineMono=next.startsWith("WAIT_")?m_monotonic.elapsed()+periodMillis(m_mode,m_submode)*2+1'500:0;if(next=="COMPLETE")m_ftSequence.insert("completionReason","Standard exchange complete");}

void AgentDigiController::checkFtSequenceTimeout(){
  if(m_ftDeadlineMono<=0||m_monotonic.elapsed()<m_ftDeadlineMono) return;
  m_ftDeadlineMono=0;const QString state=m_ftSequence.value("state").toString();QString retryKind;
  if(state=="WAIT_CALLER"){if(m_ftSequence.value("autoCq").toBool()&&m_ftSequence.value("cqTransmissions").toInt()<m_ftSequence.value("autoCqLimit").toInt())retryKind="CQ";else{m_ftSequence.insert("state","STOPPED");m_ftSequence.insert("completionReason","Unanswered CQ limit reached");}}
  else{const QHash<QString,QString> kinds{{"WAIT_REPORT","GRID"},{"WAIT_R_REPORT","REPORT"},{"WAIT_RR73","R_REPORT"},{"WAIT_FINAL_73","RR73"}};retryKind=kinds.value(state);const int retries=m_ftSequence.value("retryCount").toInt();if(!retryKind.isEmpty()&&retries>=m_ftSequence.value("retryLimit").toInt()){m_ftSequence.insert("state","FAILED");m_ftSequence.insert("completionReason","Retry limit reached");retryKind.clear();}else if(!retryKind.isEmpty())m_ftSequence.insert("retryCount",retries+1);}
  if(!retryKind.isEmpty()){QString error;if(!queueFtSequenceMessage(retryKind,&error)){m_ftSequence.insert("state","FAILED");m_ftSequence.insert("completionReason",error);}}emit snapshotChanged();
}

QJsonObject AgentDigiController::processCommand(const QJsonObject &frame,const QString &agentId,const QString &deviceId,quint64 generation) {
  const QString commandId=frame.value("commandId").toString(),action=frame.value("action").toString();
  const QJsonObject p=frame.value("parameters").toObject();
  if(!boundedId(commandId)||frame.value("agentId").toString()!=agentId||frame.value("deviceId").toString()!=deviceId||frame.value("expectedGeneration").toVariant().toULongLong()!=generation||generation==0)
    return result(frame,agentId,deviceId,generation,false,"COMMAND_SCOPE_REJECTED");
  if(const auto cached=m_commandResults.constFind(commandId);cached!=m_commandResults.cend())return cached.value();
  const qint64 valid=QDateTime::fromString(frame.value("validUntilUtc").toString(),Qt::ISODateWithMs).toMSecsSinceEpoch();
  if(valid<=QDateTime::currentMSecsSinceEpoch())return result(frame,agentId,deviceId,generation,false,"COMMAND_EXPIRED");
  if(action=="digi.control.acquire"){
    if(m_leaseExpiryMono>m_monotonic.elapsed()&&!ownsLease(frame)){stop("control taken over");}
    m_leaseBrowserSession=frame.value("browserSessionId").toString();m_leaseControlInstance=frame.value("controlInstanceId").toString();m_leaseExpiryMono=m_monotonic.elapsed()+LeaseMillis;emit snapshotChanged();return result(frame,agentId,deviceId,generation,true,"CONTROL_ACQUIRED");
  }
  if(action=="digi.stop"){
    const StopOutcome stopped=stop("operator STOP");
    if(stopped==StopOutcome::InProgress)return QJsonObject{{"type","digi.command.result"},{"protocol",QJsonObject{{"major",1},{"minor",2}}},{"commandId",frame.value("commandId")},{"agentId",agentId},{"deviceId",deviceId},{"generation",QJsonValue::fromVariant(generation)},{"ok",false},{"code","STOP_IN_PROGRESS_RX_UNCONFIRMED"}};
    return result(frame,agentId,deviceId,generation,stopped==StopOutcome::RxVerified,stopped==StopOutcome::RxVerified?"STOPPED_RX_VERIFIED":"RX_UNCONFIRMED");}
  if(!ownsLease(frame))return result(frame,agentId,deviceId,generation,false,"CONTROL_LEASE_REQUIRED");
  m_leaseExpiryMono=m_monotonic.elapsed()+LeaseMillis;
  if(action=="digi.control.renew"){emit snapshotChanged();return result(frame,agentId,deviceId,generation,true,"CONTROL_RENEWED");}
  if(action=="digi.control.release"){stop("control released");m_leaseExpiryMono=0;m_leaseBrowserSession.clear();m_leaseControlInstance.clear();return result(frame,agentId,deviceId,generation,true,"CONTROL_RELEASED");}
  if(action=="digi.history.export"){
    const QJsonObject session=retainedSession(p.value("sessionId").toString());
    if(session.isEmpty())return result(frame,agentId,deviceId,generation,false,"RETAINED_SESSION_NOT_FOUND");
    QJsonObject value=result(frame,agentId,deviceId,generation,true,"RETAINED_SESSION_EXPORT_READY");
    value.insert("exportJson",QString::fromUtf8(QJsonDocument(QJsonObject{{"schemaVersion",1},{"session",session}}).toJson(QJsonDocument::Compact)));
    m_commandResults.insert(commandId,value);return value;
  }
  if(action=="digi.history.delete"){
    if(!p.value("confirmed").toBool())return result(frame,agentId,deviceId,generation,false,"EXPLICIT_DELETE_CONFIRMATION_REQUIRED");
    QString error;const bool ok=deleteRetainedSession(p.value("sessionId").toString(),&error);return result(frame,agentId,deviceId,generation,ok,ok?"RETAINED_SESSION_DELETED":error);
  }
  if(action=="digi.history.redecode"){
    QString error;const bool ok=redecodeRetainedSession(p.value("sessionId").toString(),&error);return result(frame,agentId,deviceId,generation,ok,ok?"RETAINED_SESSION_REDECODED":error);
  }
  if(action=="digi.history.replay"){
    QString error;const bool ok=replayRetainedSession(p.value("sessionId").toString(),&error);return result(frame,agentId,deviceId,generation,ok,ok?"LOCAL_REPLAY_STARTED":error);
  }
  if(action=="digi.sequence.stop"){const StopOutcome stopped=stop("FT sequence stopped by operator");return result(frame,agentId,deviceId,generation,stopped==StopOutcome::RxVerified,stopped==StopOutcome::RxVerified?"FT_SEQUENCE_STOPPED_RX_VERIFIED":"RX_UNCONFIRMED");}
  if(m_state==State::RxUnconfirmed)return result(frame,agentId,deviceId,generation,false,"RX_UNCONFIRMED_LATCHED");
  if(action=="digi.configure"){
    const QString mode=p.value("mode").toString(),submode=p.value("submode").toString();const int index=modeIndex(mode,submode);
    const double rx=p.value("rxAudioHz").toDouble(),tx=p.value("txAudioHz").toDouble();
    const bool continuous=QStringList{"CW","RTTY","PSK31","SSTV"}.contains(mode)&&submode.isEmpty();
    if((index<0&&!continuous)||!std::isfinite(rx)||!std::isfinite(tx)||rx<100||rx>5000||tx<100||tx>5000)return result(frame,agentId,deviceId,generation,false,"INVALID_PARAMETERS");
    if(m_state!=State::Safe&&m_state!=State::Rx&&m_state!=State::RxVerified)return result(frame,agentId,deviceId,generation,false,"DIGI_BUSY_REQUIRES_DISARM");
    m_dspFuture.waitForFinished();
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
    if(m_continuousContext){shackcq_digi_context_destroy(static_cast<shackcq_digi_context *>(m_continuousContext));m_continuousContext=nullptr;}
#endif
    stopFtSequence("Mode changed");m_mode=mode;m_submode=submode;m_modeIndex=index;m_rxAudioHz=float(rx);m_txAudioHz=float(tx);m_continuousSamples.clear();m_sstv={};m_contextGeneration++;resetPrepared();emit snapshotChanged();return result(frame,agentId,deviceId,generation,true,"DIGI_CONFIGURED");
  }
  if(action=="digi.rx.start"){
    QString error;if(!configureAudio(p.value("audioProfileId").toString(),&error)||!startRx(&error))return result(frame,agentId,deviceId,generation,false,error);
    return result(frame,agentId,deviceId,generation,true,"RX_STARTED");
  }
  if(action=="digi.rx.stop"){
    stop("RX stop requested");
    if(m_state==State::RxUnconfirmed)return result(frame,agentId,deviceId,generation,false,"RX_UNCONFIRMED");
    if(m_source){m_source->stop();m_source->deleteLater();m_source=nullptr;m_input=nullptr;}finishRetainedSession();
    stopFtSequence("RX stopped");m_state=State::Safe;m_audioState="STOPPED";m_lastInputMono=0;emit snapshotChanged();return result(frame,agentId,deviceId,generation,true,"RX_STOPPED");
  }
  if(action=="digi.select"){m_selectedDecode=p.value("decodeId").toString();emit snapshotChanged();return result(frame,agentId,deviceId,generation,true,"DECODE_SELECTED_NO_TUNE_NO_TX");}
  if(action=="digi.sequence.start"){
    QString error;if(!startFtSequence(p,&error))return result(frame,agentId,deviceId,generation,false,error);
    return result(frame,agentId,deviceId,generation,true,clockGood()?"FT_SEQUENCE_STARTED_LOCAL":"FT_SEQUENCE_PREPARED_CLOCK_UNVERIFIED");
  }
  if(action=="digi.bandstack.cycle"){
    if(m_state!=State::Safe&&m_state!=State::Rx&&m_state!=State::RxVerified)return result(frame,agentId,deviceId,generation,false,"DIGI_BUSY_REQUIRES_DISARM");
    const QJsonArray entries=p.value("entries").toArray();if(entries.isEmpty())return result(frame,agentId,deviceId,generation,false,"BAND_STACK_EMPTY");
    const int direction=p.value("direction").toInt();int current=-1;for(int i=0;i<entries.size();++i)if(entries.at(i).toObject().value("frequencyHz").toVariant().toULongLong()==(m_radio?m_radio->frequencyHz():0))current=i;
    const int next=current<0?(direction>0?0:entries.size()-1):(current+direction+entries.size())%entries.size();QString error;if(!applyReceiveEntry(entries.at(next).toObject(),&error))return result(frame,agentId,deviceId,generation,false,error);
    return result(frame,agentId,deviceId,generation,true,"BAND_STACK_RECALL_CONFIRMED");
  }
  if(action=="digi.scanner.start"){
    if(m_state!=State::Safe&&m_state!=State::Rx&&m_state!=State::RxVerified)return result(frame,agentId,deviceId,generation,false,"DIGI_BUSY_REQUIRES_DISARM");
    if(m_radio&&m_radio->transmitting().value_or(true))return result(frame,agentId,deviceId,generation,false,"RECEIVER_NOT_VERIFIED_SAFE");
    m_scannerEntries=p.value("entries").toArray();m_scannerIndex=0;m_scannerRemaining=std::clamp(p.value("iterations").toInt(),1,100)*m_scannerEntries.size();m_scanner.setProperty("dwellMillis",std::clamp(p.value("dwellMillis").toInt(),1'000,120'000));scannerStep();return result(frame,agentId,deviceId,generation,true,"SCANNER_STARTED_LOCAL_BOUNDED");
  }
  if(action=="digi.scanner.stop"){m_scanner.stop();m_scannerEntries={};m_scannerRemaining=0;return result(frame,agentId,deviceId,generation,true,"SCANNER_STOPPED");}
  if(action=="digi.prepare"){
    QString error;if(!prepare(p,&error))return result(frame,agentId,deviceId,generation,false,error);
    return result(frame,agentId,deviceId,generation,true,"READY_FOR_EXPLICIT_ARM");
  }
  if(action=="digi.sstv.prepare"){
    QString error;if(m_mode!="SSTV")return result(frame,agentId,deviceId,generation,false,"SSTV_MODE_REQUIRED");if(!prepareSstv(p,&error))return result(frame,agentId,deviceId,generation,false,error);return result(frame,agentId,deviceId,generation,true,"SSTV_IMAGE_READY_FOR_EXPLICIT_ARM");
  }
  if(action=="digi.arm"){
    if(m_state!=State::Ready||p.value("messageRevision").toInt()!=m_messageRevision)return result(frame,agentId,deviceId,generation,false,"PREPARED_REVISION_REQUIRED");
    if(!m_serverTxPermitted)return result(frame,agentId,deviceId,generation,false,"SERVER_TX_DISABLED");
    if(!m_localTxPermitted)return result(frame,agentId,deviceId,generation,false,"LOCAL_TX_DISABLED");
    if(!m_hardwareAccepted)return result(frame,agentId,deviceId,generation,false,"HARDWARE_ACCEPTANCE_REQUIRED");
    if(m_audioState!="READY"&&m_audioState!="LIVE")return result(frame,agentId,deviceId,generation,false,"TX_AUDIO_NOT_READY");
    if(m_lastInputMono<=0||m_monotonic.elapsed()-m_lastInputMono>2'000)return result(frame,agentId,deviceId,generation,false,"FRESH_RX_REQUIRED");
    const auto ptt=pttReadback();if(!ptt||*ptt)return result(frame,agentId,deviceId,generation,false,"PTT_NOT_VERIFIED_OFF");
    if(periodMillis(m_mode,m_submode)>0)return result(frame,agentId,deviceId,generation,false,"CLOCK_QUALITY_UNVERIFIED");
    m_armExpiryMono=m_monotonic.elapsed()+std::clamp<qint64>(p.value("validWindowMillis").toInteger(),1'000,120'000);m_state=State::Armed;emit snapshotChanged();return result(frame,agentId,deviceId,generation,true,"ARMED_FOR_VALID_WINDOW");
  }
  if(action=="digi.send"){
    if(m_state!=State::Armed||m_armExpiryMono<=m_monotonic.elapsed()||p.value("messageRevision").toInt()!=m_messageRevision)return result(frame,agentId,deviceId,generation,false,"ARM_EXPIRED_OR_STALE");
    if(m_sendScheduled)return result(frame,agentId,deviceId,generation,false,"SEND_ALREADY_SCHEDULED");
    m_repeatsRemaining=m_maxRepeats;QString error;if(!scheduleTransmit(&error)){m_state=State::Ready;return result(frame,agentId,deviceId,generation,false,error);}
    return result(frame,agentId,deviceId,generation,true,"SCHEDULED_BY_LOCAL_MONOTONIC_CLOCK");
  }
  return result(frame,agentId,deviceId,generation,false,"ACTION_PROHIBITED");
}

bool AgentDigiController::configureAudio(const QString &profileId,QString *error){if(m_profile.isEmpty()||m_profile.value("id").toString()!=profileId){if(error)*error="AUDIO_PROFILE_REQUIRED";return false;}m_sampleRate=m_profile.value("sampleRate",48'000).toInt();m_channels=1;m_audioState="READY";m_audioDetail="Selected device identities and mono Int16 format validated";return true;}

bool AgentDigiController::startRx(QString *error){QAudioDevice input;for(const auto &d:QMediaDevices::audioInputs())if(audioId(d)==m_profile.value("inputDeviceId").toString())input=d;if(input.isNull()){if(error)*error="AUDIO_INPUT_MISSING";m_audioState="ROUTE_LOST";return false;}QAudioFormat f;f.setSampleRate(m_sampleRate);m_channels=std::max(1,input.preferredFormat().channelCount());const int selectedChannel=m_profile.value("inputChannel").toInt();if(selectedChannel>=m_channels){if(error)*error="AUDIO_INPUT_CHANNEL_UNAVAILABLE";m_audioState="ERROR";return false;}f.setChannelCount(m_channels);f.setSampleFormat(QAudioFormat::Int16);if(!input.isFormatSupported(f)){if(error)*error="AUDIO_FORMAT_UNSUPPORTED";m_audioState="ERROR";return false;}if(m_source){m_source->stop();m_source->deleteLater();finishRetainedSession();}m_source=new QAudioSource(input,f,this);m_input=m_source->start();if(!m_input){if(error)*error="AUDIO_OPEN_FAILED";return false;}connect(m_input,&QIODevice::readyRead,this,&AgentDigiController::consumeInput);m_sessionId=QUuid::createUuid().toString(QUuid::WithoutBraces);m_capture.clear();m_captureSlotStart=0;m_resamplePhase=0;m_resampleSum=0;m_resampleCount=0;resetClockEvidence();beginRetainedSession();m_state=State::Rx;m_audioState="LIVE";m_audioDetail=QStringLiteral("Input channel %1 active at %2 Hz; anti-aliased 12 kHz DSP remains local").arg(selectedChannel+1).arg(m_sampleRate);emit snapshotChanged();return true;}

void AgentDigiController::consumeInput() {
  if (!m_input) return;
  const QByteArray bytes = m_input->read(256 * 1024);
  const qsizetype count = bytes.size() / (2 * std::max(1,m_channels));
  if (count <= 0) return;
  const auto *pcm = reinterpret_cast<const qint16 *>(bytes.constData());
  double sum = 0;
  float peak = 0;
  QVector<qint16> retained;
  retained.reserve(qsizetype(double(count)*12'000.0/double(m_sampleRate))+2);
  const int selectedChannel=m_profile.value("inputChannel").toInt();
  for (qsizetype i = 0; i < count; ++i) {
    const float value = float(qFromLittleEndian<qint16>(pcm + i*m_channels+selectedChannel)) / 32768.0f;
    sum += double(value) * value;
    peak = std::max(peak, std::abs(value));
    m_resampleSum+=value;++m_resampleCount;m_resamplePhase += 12'000.0 / double(m_sampleRate);
    if (m_resamplePhase >= 1.0) {
      const float filtered=float(m_resampleSum/double(m_resampleCount));
      if (m_modeIndex >= 0) m_capture.push_back(filtered);
      m_displaySamples.push_back(filtered);
      if (QStringList{"CW","RTTY","PSK31","SSTV"}.contains(m_mode)) m_continuousSamples.push_back(filtered);
      retained.push_back(qint16(std::clamp(filtered,-1.0f,1.0f)*32767.0f));
      m_resamplePhase -= 1.0;
      m_resampleSum=0;m_resampleCount=0;
    }
  }
  appendRetainedSamples(retained);
  updateClockEvidence(retained.size());
  if(m_displaySamples.size()>16'384)m_displaySamples.remove(0,m_displaySamples.size()-16'384);
  if(m_continuousSamples.size()>120'000)m_continuousSamples.remove(0,m_continuousSamples.size()-120'000);
  m_rms = float(std::sqrt(sum / double(count)));
  m_peak = peak;
  m_clipped = peak >= 0.999f;
  m_lastInputMono = m_monotonic.elapsed();
  const qint64 period = periodMillis(m_mode, m_submode);
  const qint64 now = QDateTime::currentMSecsSinceEpoch();
  const qint64 slot = period ? (now / period) * period : now;
  if (m_captureSlotStart == 0) m_captureSlotStart = slot;
  if (period && slot != m_captureSlotStart) {
    QVector<float> completed;
    completed.swap(m_capture);
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
    const qint64 completedSlot = m_captureSlotStart;
#endif
    m_captureSlotStart = slot;
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
    if(m_slotDecodeInFlight)return;
    const int mode = m_modeIndex;
    const quint64 contextGeneration=m_contextGeneration;
    QPointer<AgentDigiController> self(this);
    m_slotDecodeInFlight=true;
    (void)QtConcurrent::run([self, completed = std::move(completed), completedSlot, mode, contextGeneration] {
      std::array<char, 64 * 1024> output{};
      const int size = shackcq_digi_decode_slot(mode, completed.constData(),
          size_t(completed.size()), 12'000, output.data(), output.size());
      if(!self)return;
      QJsonParseError parse;
      const QJsonObject decoded=size>0?QJsonDocument::fromJson(QByteArray(output.data(), size), &parse).object():QJsonObject{};
      QMetaObject::invokeMethod(self, [self, decoded, completedSlot, contextGeneration, valid=size>0&&parse.error==QJsonParseError::NoError] {
        if (!self) return;
        self->m_slotDecodeInFlight=false;
        if(!valid||self->m_contextGeneration!=contextGeneration)return;
        for (const auto &value : decoded.value("decodes").toArray()) {
          const QJsonObject row = value.toObject();
          const QJsonObject retained{{"id", QUuid::createUuid().toString(QUuid::WithoutBraces)},
            {"slotStartMillis", completedSlot}, {"source", "LIVE_CAPTURE"}, {"exactSlotTiming", self->clockGood()},
            {"snr", row.value("snr")}, {"dt", row.value("dt")},
            {"audioHz", row.value("frequencyHz")}, {"text", row.value("text").toString().left(512)}};
          self->m_decodes.prepend(retained);self->appendRetainedDecode(retained);self->advanceFtSequence(retained);
        }
        while (self->m_decodes.size() > 256) self->m_decodes.removeLast();
        emit self->snapshotChanged();
      }, Qt::QueuedConnection);
    });
#endif
  }
  emit snapshotChanged();
}

bool AgentDigiController::prepare(const QJsonObject &p,QString *error){if(m_state!=State::Safe&&m_state!=State::Rx&&m_state!=State::RxVerified){if(error)*error="DIGI_BUSY_REQUIRES_DISARM";return false;}const QString text=p.value("message").toString();if(text.isEmpty()||text.size()>128){if(error)*error="MESSAGE_INVALID";return false;}m_maxRepeats=std::clamp(p.value("maxRepeats").toInt(),1,10);m_state=State::Preparing;QVector<float> samples;
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
  const QByteArray utf8=text.toUtf8();int needed=-1;if(m_modeIndex>=0)needed=shackcq_digi_encode_slot(m_modeIndex,utf8.constData(),m_txAudioHz,nullptr,0);else if(m_mode=="CW")needed=shackcq_digi_encode_cw(utf8.constData(),20,m_txAudioHz,12'000,nullptr,0);else if(m_mode=="RTTY")needed=shackcq_digi_encode_rtty(utf8.constData(),12'000,false,nullptr,0);else if(m_mode=="PSK31")needed=shackcq_digi_encode_psk31(utf8.constData(),m_txAudioHz,nullptr,0);
  if(needed<=0||needed>MaximumWaveformSamples){m_state=State::Safe;if(error)*error="ENCODER_FAILED_OR_BOUNDED";return false;}samples.resize(needed);int written=-1;if(m_modeIndex>=0)written=shackcq_digi_encode_slot(m_modeIndex,utf8.constData(),m_txAudioHz,samples.data(),samples.size());else if(m_mode=="CW")written=shackcq_digi_encode_cw(utf8.constData(),20,m_txAudioHz,12'000,samples.data(),samples.size());else if(m_mode=="RTTY")written=shackcq_digi_encode_rtty(utf8.constData(),12'000,false,samples.data(),samples.size());else if(m_mode=="PSK31")written=shackcq_digi_encode_psk31(utf8.constData(),m_txAudioHz,samples.data(),samples.size());if(written!=needed){m_state=State::Safe;if(error)*error="ENCODER_SHORT_WRITE";return false;}
#else
  Q_UNUSED(samples);m_state=State::Safe;if(error)*error="DIGI_NOT_COMPILED";return false;
#endif
  return storeWaveform(samples,text,error);}

bool AgentDigiController::prepareSstv(const QJsonObject &p,QString *error){if(m_state!=State::Safe&&m_state!=State::Rx&&m_state!=State::RxVerified){if(error)*error="DIGI_BUSY_REQUIRES_DISARM";return false;}const QByteArray encoded=QByteArray::fromBase64(p.value("dataBase64").toString().toLatin1(),QByteArray::AbortOnBase64DecodingErrors);if(encoded.isEmpty()||encoded.size()>33'000){if(error)*error="SSTV_IMAGE_SIZE_INVALID";return false;}QImage source;if(!source.loadFromData(encoded)||(source.format()!=QImage::Format_RGB32&&source.format()!=QImage::Format_ARGB32&&source.format()!=QImage::Format_ARGB32_Premultiplied)){source=source.convertToFormat(QImage::Format_RGB32);}if(source.isNull()||source.width()>4096||source.height()>4096){if(error)*error="SSTV_IMAGE_INVALID";return false;}const int sstvMode=p.value("sstvMode").toInt();const bool robot=sstvMode>=7&&sstvMode<=9;const QSize target(robot?QSize(320,240):QSize(320,256));const QImage scaled=source.scaled(target,Qt::KeepAspectRatioByExpanding,Qt::SmoothTransformation);const QImage image=scaled.copy((scaled.width()-target.width())/2,(scaled.height()-target.height())/2,target.width(),target.height()).convertToFormat(QImage::Format_RGB888);QByteArray rgb(reinterpret_cast<const char *>(image.constBits()),image.sizeInBytes());QVector<float> samples;
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
const int needed=shackcq_digi_encode_sstv(sstvMode,reinterpret_cast<const uint8_t *>(rgb.constData()),image.width(),image.height(),12'000,nullptr,0);if(needed<=0||needed>MaximumWaveformSamples){if(error)*error="SSTV_ENCODER_FAILED_OR_BOUNDED";return false;}samples.resize(needed);if(shackcq_digi_encode_sstv(sstvMode,reinterpret_cast<const uint8_t *>(rgb.constData()),image.width(),image.height(),12'000,samples.data(),samples.size())!=needed){if(error)*error="SSTV_ENCODER_SHORT_WRITE";return false;}
#else
Q_UNUSED(samples);if(error)*error="DIGI_NOT_COMPILED";return false;
#endif
m_maxRepeats=1;return storeWaveform(samples,QStringLiteral("SSTV:%1").arg(p.value("objectId").toString()),error);}

bool AgentDigiController::storeWaveform(const QVector<float> &samples,const QString &label,QString *error){if(samples.isEmpty()){if(error)*error="ENCODER_EMPTY";return false;}const int outputRate=m_profile.value("sampleRate",48'000).toInt();m_txPcm.clear();const qint64 outputSamples=qint64(samples.size())*outputRate/12'000;if(outputSamples<=0||outputSamples>MaximumWaveformSamples){if(error)*error="WAVEFORM_LIMIT";return false;}m_txPcm.reserve(int(outputSamples*2));for(qint64 i=0;i<outputSamples;++i){const float v=samples.at(std::min<qint64>(samples.size()-1,i*12'000/outputRate));const qint16 pcm=qint16(std::clamp(v,-1.0f,1.0f)*32767.0f);m_txPcm.append(char(pcm&0xff));m_txPcm.append(char((pcm>>8)&0xff));}m_preparedMessage=label;m_preparedFrequencyHz=m_radio?m_radio->frequencyHz():0;m_preparedRadioMode=m_radio?m_radio->mode():QString{};m_preparedFilterHz=m_radio?m_radio->filterHz():0;m_messageRevision++;m_state=State::Ready;emit snapshotChanged();return true;}

bool AgentDigiController::setPtt(bool enabled){if(m_pttHook)return m_pttHook(enabled);return m_radio&&m_radio->requestDigiPtt(enabled);}
std::optional<bool> AgentDigiController::pttReadback() const {if(m_readbackHook)return m_readbackHook();return m_radio?m_radio->digiPttReadback():std::nullopt;}
bool AgentDigiController::scheduleTransmit(QString *error){
  const qint64 period=periodMillis(m_mode,m_submode),now=QDateTime::currentMSecsSinceEpoch();
  const qint64 slot=period?((now/period)+1)*period:now+100,delay=slot-now;
  if(m_monotonic.elapsed()+delay>m_armExpiryMono){if(error)*error="MISSED_VALID_WINDOW";return false;}
  QString audioError;if(!configureAudio(m_profile.value("id").toString(),&audioError)){if(error)*error=audioError;return false;}
  QAudioDevice output;for(const auto &d:QMediaDevices::audioOutputs())if(audioId(d)==m_profile.value("outputDeviceId").toString())output=d;
  if(output.isNull()){if(error)*error="SELECTED_OUTPUT_DEVICE_MISSING";return false;}
  QAudioFormat format;format.setSampleRate(m_sampleRate);format.setChannelCount(1);format.setSampleFormat(QAudioFormat::Int16);
  if(!output.isFormatSupported(format)){if(error)*error="SELECTED_OUTPUT_FORMAT_UNAVAILABLE";return false;}
  if(m_sink){m_sink->reset();m_sink->deleteLater();}
  m_sink=new QAudioSink(output,format,this);m_nextSlotEpoch=slot;m_sendScheduled=true;const quint64 intent=++m_sendIntentGeneration;
  connect(m_sink,&QAudioSink::stateChanged,this,[this,intent](QAudio::State){if(intent==m_sendIntentGeneration&&m_sink&&m_sink->error()!=QAudio::NoError)stop("TX audio sink error");});
  QTimer::singleShot(std::max<qint64>(0,delay-80),this,[this,intent]{
    if(intent!=m_sendIntentGeneration||m_state!=State::Armed||m_armExpiryMono<=m_monotonic.elapsed())return;
    const auto before=pttReadback();if(!before||*before){stop("PTT not verified off immediately before keying");return;}
    m_pttReleaseRequired=true;
    if(!setPtt(true)){stop("PTT confirmation failed");return;}
    const auto after=pttReadback();if(!after||!*after){stop("PTT on readback unconfirmed");return;}
    m_pttOwned=true;m_state=State::PttConfirmed;emit snapshotChanged();
  });
  QTimer::singleShot(std::max<qint64>(0,delay),this,[this,intent,slot]{
    if(intent!=m_sendIntentGeneration)return;
    m_sendScheduled=false;
    if(m_state!=State::PttConfirmed||std::llabs(QDateTime::currentMSecsSinceEpoch()-slot)>120){stop("late waveform start");return;}
    m_output.setData(m_txPcm);if(!m_output.open(QIODevice::ReadOnly)){stop("TX buffer open failed");return;}
    m_sink->start(&m_output);if(m_sink->error()!=QAudio::NoError){stop("TX audio start failed");return;}
    m_state=State::Transmitting;--m_repeatsRemaining;m_txFinish.start(std::min<qint64>(120'000,(m_txPcm.size()/2)*1000LL/m_sampleRate+500));emit snapshotChanged();
  });
  return true;
}
void AgentDigiController::finishTransmit(){if(m_sink){m_sink->reset();m_sink->deleteLater();m_sink=nullptr;}m_output.close();m_state=State::Stopping;const bool released=!m_pttReleaseRequired||setPtt(false);const auto rx=pttReadback();const bool safe=released&&rx&&!*rx;m_pttOwned=false;m_pttReleaseRequired=!safe;m_state=safe?State::RxVerified:State::RxUnconfirmed;if(!safe){m_stopRetryAttempts=0;m_nextStopRetryMono=m_monotonic.elapsed()+StopRetryMillis;}if(m_state==State::RxVerified&&m_repeatsRemaining>0&&m_armExpiryMono>m_monotonic.elapsed()){m_state=State::Armed;QString error;if(scheduleTransmit(&error)){emit snapshotChanged();return;}}resetPrepared();if(safe)noteFtTransmitComplete();else stopFtSequence("RX recovery unconfirmed");emit snapshotChanged();}
AgentDigiController::StopOutcome AgentDigiController::stop(const QString &reason, bool requireRadioStop){stopFtSequence(reason);if(m_stopInProgress)return StopOutcome::InProgress;m_stopRetryAttempts=0;m_nextStopRetryMono=0;return performStop(false,requireRadioStop);}
AgentDigiController::StopOutcome AgentDigiController::performStop(bool autonomousRetry, bool requireRadioStop){if(m_stopInProgress)return StopOutcome::InProgress;const bool hardwareRisk=m_pttReleaseRequired||m_pttOwned||m_state==State::PttConfirmed||m_state==State::Transmitting||m_state==State::Stopping||m_sink;m_stopInProgress=true;++m_sendIntentGeneration;m_sendScheduled=false;m_contextGeneration++;m_scanner.stop();m_scannerEntries={};m_scannerRemaining=0;m_txFinish.stop();if(m_sink){m_sink->reset();m_sink->deleteLater();m_sink=nullptr;}m_output.close();const bool radioStopVerified=!m_radio||(!requireRadioStop&&!hardwareRisk)||m_radio->globalStop();const bool helperRxProof=m_radio&&m_radio->backend()=="hamlib"&&(requireRadioStop||hardwareRisk)&&radioStopVerified;if(m_pttReleaseRequired){m_state=State::Stopping;const bool released=helperRxProof||setPtt(false);const auto rx=helperRxProof?std::optional<bool>(false):pttReadback();const bool safe=radioStopVerified&&released&&rx&&!*rx;m_pttOwned=false;m_pttReleaseRequired=!safe;m_state=safe?State::RxVerified:State::RxUnconfirmed;}else if(!radioStopVerified)m_state=State::RxUnconfirmed;else if(m_state!=State::RxUnconfirmed)m_state=m_source?State::Rx:State::Safe;resetPrepared();const bool unconfirmed=m_state==State::RxUnconfirmed||m_pttReleaseRequired;if(unconfirmed&&m_stopRetryAttempts<MaximumStopRetries)m_nextStopRetryMono=m_monotonic.elapsed()+StopRetryMillis;else m_nextStopRetryMono=0;if(!unconfirmed){m_stopRetryAttempts=0;m_nextStopRetryMono=0;}Q_UNUSED(autonomousRetry);m_stopInProgress=false;emit snapshotChanged();return unconfirmed?StopOutcome::RxUnconfirmed:StopOutcome::RxVerified;}
void AgentDigiController::handleUnsafeRadioLoss(bool rxVerified){const bool active=m_pttReleaseRequired||m_pttOwned||m_state==State::PttConfirmed||m_state==State::Transmitting||m_state==State::Stopping||m_sink;if(!active)return;++m_sendIntentGeneration;m_sendScheduled=false;m_contextGeneration++;m_txFinish.stop();if(m_sink){m_sink->reset();m_sink->deleteLater();m_sink=nullptr;}m_output.close();m_pttOwned=false;m_pttReleaseRequired=!rxVerified;m_state=rxVerified?State::RxVerified:State::RxUnconfirmed;resetPrepared();m_stopRetryAttempts=0;m_nextStopRetryMono=rxVerified?0:m_monotonic.elapsed()+StopRetryMillis;emit snapshotChanged();}
void AgentDigiController::resetPrepared(){m_txPcm.clear();m_preparedMessage.clear();m_preparedFrequencyHz=0;m_preparedRadioMode.clear();m_preparedFilterHz=0;m_armExpiryMono=0;m_nextSlotEpoch=0;m_repeatsRemaining=0;}
void AgentDigiController::expireLease(){if(m_leaseExpiryMono>0&&m_leaseExpiryMono<=m_monotonic.elapsed()){stop("control lease expired");m_leaseExpiryMono=0;m_leaseBrowserSession.clear();m_leaseControlInstance.clear();}if(m_nextStopRetryMono>0&&m_nextStopRetryMono<=m_monotonic.elapsed()&&m_stopRetryAttempts<MaximumStopRetries){++m_stopRetryAttempts;m_nextStopRetryMono=0;performStop(true);}if(m_armExpiryMono>0&&m_armExpiryMono<=m_monotonic.elapsed()&&m_state==State::Armed){m_state=State::Ready;m_armExpiryMono=0;emit snapshotChanged();}if(m_state==State::Rx&&m_lastInputMono>0&&m_monotonic.elapsed()-m_lastInputMono>2'000){m_audioState="SILENT";m_audioDetail="No fresh samples from selected input";emit snapshotChanged();}checkFtSequenceTimeout();processDisplayAndContinuous();}

bool AgentDigiController::applyReceiveEntry(const QJsonObject &entry,QString *error){
  if(!m_radio||m_radio->transmitting().value_or(true)){if(error)*error="RECEIVER_NOT_VERIFIED_SAFE";return false;}
  const quint64 before=m_radio->frequencyHz();const QString beforeMode=m_radio->mode();const int beforeFilter=m_radio->filterHz();
  const quint64 hz=entry.value("frequencyHz").toVariant().toULongLong();const QString mode=entry.value("mode").toString();const int filter=entry.value("filterHz").toInt();
  if(!m_radio->requestFrequency(hz)||!m_radio->requestMode(mode)||!m_radio->requestFilter(filter)||m_radio->frequencyHz()!=hz||m_radio->mode()!=mode||m_radio->filterHz()!=filter){
    if(m_radio->frequencyHz()==hz){(void)m_radio->requestFrequency(before);(void)m_radio->requestMode(beforeMode);(void)m_radio->requestFilter(beforeFilter);}if(error)*error="RECEIVE_CHANGE_PARTIAL_OR_UNCONFIRMED";return false;
  }
  m_capture.clear();m_captureSlotStart=0;m_contextGeneration++;m_selectedDecode.clear();emit snapshotChanged();return true;
}

void AgentDigiController::scannerStep(){
  if(m_scannerRemaining<=0||m_scannerEntries.isEmpty()){m_scannerEntries={};return;}
  if(m_state!=State::Safe&&m_state!=State::Rx&&m_state!=State::RxVerified){m_scannerEntries={};m_scannerRemaining=0;return;}
  QString error;if(!applyReceiveEntry(m_scannerEntries.at(m_scannerIndex).toObject(),&error)){m_scannerEntries={};m_scannerRemaining=0;return;}
  m_scannerIndex=(m_scannerIndex+1)%m_scannerEntries.size();--m_scannerRemaining;if(m_scannerRemaining>0)m_scanner.start(m_scanner.property("dwellMillis").toInt());
}

QString AgentDigiController::retainedAudioPath(const QString &sessionId) const {
  return QDir(m_retainedRoot).filePath(sessionId + ".pcm");
}

QJsonObject AgentDigiController::retainedSession(const QString &sessionId) const {
  for (const auto &value : m_retainedSessions) {
    const QJsonObject row=value.toObject();
    if(row.value("id").toString()==sessionId)return row;
  }
  return {};
}

void AgentDigiController::loadRetainedSessions() {
  QDir().mkpath(m_retainedRoot);
  QFile file(QDir(m_retainedRoot).filePath("sessions.json"));
  if(!file.open(QIODevice::ReadOnly))return;
  const QJsonDocument document=QJsonDocument::fromJson(file.readAll());
  if(!document.isArray())return;
  for(const auto &value:document.array()){
    const QJsonObject row=value.toObject();const QString id=row.value("id").toString();
    if(!boundedId(id)||!QFile::exists(retainedAudioPath(id)))continue;
    m_retainedSessions.append(row);
    if(m_retainedSessions.size()>=MaximumRetainedSessions)break;
  }
}

void AgentDigiController::persistRetainedSessions() {
  QDir().mkpath(m_retainedRoot);
  QSaveFile file(QDir(m_retainedRoot).filePath("sessions.json"));
  if(!file.open(QIODevice::WriteOnly))return;
  file.write(QJsonDocument(m_retainedSessions).toJson(QJsonDocument::Compact));
  file.commit();
}

void AgentDigiController::beginRetainedSession() {
  if(!boundedId(m_sessionId))return;
  QFile file(retainedAudioPath(m_sessionId));
  if(!file.open(QIODevice::WriteOnly|QIODevice::Truncate)){m_audioDetail="RX active; local retained recording unavailable";return;}
  file.close();m_sessionStartedUtc=QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);m_retainedSampleCount=0;
  m_retainedSessions.prepend(QJsonObject{{"id",m_sessionId},{"startedUtc",m_sessionStartedUtc},{"endedUtc",QJsonValue::Null},{"mode",m_mode},{"submode",m_submode.isEmpty()?QJsonValue::Null:QJsonValue(m_submode)},{"dialFrequencyHz",m_radio&&m_radio->frequencyHz()?QJsonValue::fromVariant(m_radio->frequencyHz()):QJsonValue::Null},{"audioProfileId",m_profile.value("id").toString()},{"sampleRate",12'000},{"channels",1},{"sampleCount",0},{"byteSize",0},{"decodeCount",0},{"decodes",QJsonArray{}},{"recordingLocal",true}});
  persistRetainedSessions();
}

void AgentDigiController::appendRetainedSamples(const QVector<qint16> &samples) {
  if(samples.isEmpty()||m_sessionId.isEmpty())return;
  QFile file(retainedAudioPath(m_sessionId));if(!file.open(QIODevice::WriteOnly|QIODevice::Append))return;
  QByteArray bytes;bytes.resize(samples.size()*2);auto *target=reinterpret_cast<uchar *>(bytes.data());
  for(qsizetype i=0;i<samples.size();++i)qToLittleEndian<qint16>(samples.at(i),target+i*2);
  if(file.size()+bytes.size()>MaximumRetainedBytes){m_audioDetail="RX active; retained recording reached the local 64 MB bound";return;}
  const qint64 before=m_retainedSampleCount;file.write(bytes);m_retainedSampleCount+=samples.size();
  if(before/120'000!=m_retainedSampleCount/120'000){for(qsizetype i=0;i<m_retainedSessions.size();++i){QJsonObject row=m_retainedSessions.at(i).toObject();if(row.value("id").toString()!=m_sessionId)continue;row.insert("sampleCount",QJsonValue::fromVariant(m_retainedSampleCount));row.insert("byteSize",QJsonValue::fromVariant(file.size()));m_retainedSessions.replace(i,row);break;}persistRetainedSessions();}
}

void AgentDigiController::appendRetainedDecode(const QJsonObject &decode) {
  if(m_sessionId.isEmpty())return;
  for(qsizetype i=0;i<m_retainedSessions.size();++i){QJsonObject row=m_retainedSessions.at(i).toObject();if(row.value("id").toString()!=m_sessionId)continue;QJsonArray decodes=row.value("decodes").toArray();decodes.append(decode);while(decodes.size()>64)decodes.removeFirst();row.insert("decodes",decodes);row.insert("decodeCount",decodes.size());m_retainedSessions.replace(i,row);persistRetainedSessions();return;}
}

void AgentDigiController::finishRetainedSession() {
  if(m_sessionId.isEmpty())return;
  for(qsizetype i=0;i<m_retainedSessions.size();++i){QJsonObject row=m_retainedSessions.at(i).toObject();if(row.value("id").toString()!=m_sessionId)continue;const QFileInfo info(retainedAudioPath(m_sessionId));row.insert("endedUtc",QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs));row.insert("sampleCount",QJsonValue::fromVariant(m_retainedSampleCount));row.insert("byteSize",QJsonValue::fromVariant(info.size()));m_retainedSessions.replace(i,row);break;}
  qint64 bytes=0;for(const auto &value:m_retainedSessions)bytes+=value.toObject().value("byteSize").toVariant().toLongLong();
  while(m_retainedSessions.size()>MaximumRetainedSessions||bytes>MaximumRetainedBytes){const QJsonObject row=m_retainedSessions.last().toObject();m_retainedSessions.removeLast();const qint64 removed=row.value("byteSize").toVariant().toLongLong();QFile::remove(retainedAudioPath(row.value("id").toString()));bytes-=removed;}
  persistRetainedSessions();m_sessionStartedUtc.clear();m_retainedSampleCount=0;
}

bool AgentDigiController::deleteRetainedSession(const QString &sessionId,QString *error) {
  if(sessionId==m_sessionId&&m_source){if(error)*error="ACTIVE_SESSION_DELETE_PROHIBITED";return false;}
  for(qsizetype i=0;i<m_retainedSessions.size();++i)if(m_retainedSessions.at(i).toObject().value("id").toString()==sessionId){m_retainedSessions.removeAt(i);QFile::remove(retainedAudioPath(sessionId));persistRetainedSessions();emit snapshotChanged();return true;}
  if(error) *error="RETAINED_SESSION_NOT_FOUND";
  return false;
}

bool AgentDigiController::redecodeRetainedSession(const QString &sessionId,QString *error) {
  const QJsonObject session=retainedSession(sessionId);if(session.isEmpty()){if(error)*error="RETAINED_SESSION_NOT_FOUND";return false;}
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
  const int index=modeIndex(session.value("mode").toString(),session.value("submode").toString());if(index<0){if(error)*error="REDECODE_REQUIRES_SLOTTED_MODE";return false;}
  QFile file(retainedAudioPath(sessionId));if(!file.open(QIODevice::ReadOnly)||file.size()>MaximumRetainedBytes){if(error)*error="RETAINED_AUDIO_UNAVAILABLE";return false;}const QByteArray bytes=file.readAll();QVector<float> samples(bytes.size()/2);const auto *pcm=reinterpret_cast<const uchar *>(bytes.constData());for(qsizetype i=0;i<samples.size();++i)samples[i]=float(qFromLittleEndian<qint16>(pcm+i*2))/32768.0f;
  const qsizetype slotSamples=std::max<qsizetype>(1,periodMillis(session.value("mode").toString(),session.value("submode").toString())*12);QJsonArray additions;
  for(qsizetype start=0;start+slotSamples<=samples.size()&&additions.size()<64;start+=slotSamples){std::array<char,64*1024> output{};const int size=shackcq_digi_decode_slot(index,samples.constData()+start,size_t(slotSamples),12'000,output.data(),output.size());QJsonParseError parse;const QJsonObject decoded=size>0?QJsonDocument::fromJson(QByteArray(output.data(),size),&parse).object():QJsonObject{};if(parse.error!=QJsonParseError::NoError)continue;for(const auto &value:decoded.value("decodes").toArray()){const QJsonObject source=value.toObject();additions.append(QJsonObject{{"id",QUuid::createUuid().toString(QUuid::WithoutBraces)},{"slotStartMillis",QDateTime::fromString(session.value("startedUtc").toString(),Qt::ISODateWithMs).toMSecsSinceEpoch()+start*1000/12'000},{"source","REFERENCE_RECORDING"},{"exactSlotTiming",false},{"snr",source.value("snr")},{"dt",source.value("dt")},{"audioHz",source.value("frequencyHz")},{"text",source.value("text").toString().left(512)}});}}
  for(const auto &value:additions) m_decodes.prepend(value.toObject());
  while(m_decodes.size()>256) m_decodes.removeLast();
  for(qsizetype i=0;i<m_retainedSessions.size();++i){QJsonObject row=m_retainedSessions.at(i).toObject();if(row.value("id").toString()!=sessionId)continue;row.insert("decodes",additions);row.insert("decodeCount",additions.size());row.insert("lastRedecodedUtc",QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs));m_retainedSessions.replace(i,row);break;}persistRetainedSessions();emit snapshotChanged();return true;
#else
  Q_UNUSED(session);if(error)*error="DIGI_NOT_COMPILED";return false;
#endif
}

bool AgentDigiController::replayRetainedSession(const QString &sessionId,QString *error) {
  if(m_state!=State::Safe&&m_state!=State::Rx&&m_state!=State::RxVerified){if(error)*error="DIGI_BUSY_REQUIRES_DISARM";return false;}const auto ptt=pttReadback();if(!ptt||*ptt){if(error)*error="PTT_NOT_VERIFIED_OFF";return false;}if(retainedSession(sessionId).isEmpty()){if(error)*error="RETAINED_SESSION_NOT_FOUND";return false;}QFile file(retainedAudioPath(sessionId));if(!file.open(QIODevice::ReadOnly)||file.size()>MaximumRetainedBytes){if(error)*error="RETAINED_AUDIO_UNAVAILABLE";return false;}QAudioDevice output;for(const auto &device:QMediaDevices::audioOutputs())if(audioId(device)==m_profile.value("outputDeviceId").toString())output=device;if(output.isNull()){if(error)*error="SELECTED_OUTPUT_DEVICE_MISSING";return false;}QAudioFormat format;format.setSampleRate(12'000);format.setChannelCount(1);format.setSampleFormat(QAudioFormat::Int16);if(!output.isFormatSupported(format)){if(error)*error="REPLAY_OUTPUT_FORMAT_UNAVAILABLE";return false;}if(m_sink){m_sink->reset();m_sink->deleteLater();}m_output.close();m_output.setData(file.readAll());if(!m_output.open(QIODevice::ReadOnly)){if(error)*error="REPLAY_BUFFER_OPEN_FAILED";return false;}m_sink=new QAudioSink(output,format,this);m_sink->start(&m_output);if(m_sink->error()!=QAudio::NoError){if(error)*error="REPLAY_AUDIO_START_FAILED";return false;}return true;
}

void AgentDigiController::processDisplayAndContinuous(){
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
  if(m_dspInFlight||m_displaySamples.size()<2048)return;
  const qsizetype displayStart=std::max<qsizetype>(0,m_displaySamples.size()-4096);
  QVector<float> display=m_displaySamples.sliced(displayStart);m_displaySamples.clear();QVector<float> continuous;continuous.swap(m_continuousSamples);const QString mode=m_mode;const float pitch=m_rxAudioHz;const quint64 contextGeneration=m_contextGeneration;if(!m_continuousContext&&QStringList{"CW","RTTY","SSTV"}.contains(mode))m_continuousContext=shackcq_digi_context_create(12'000,pitch,false,2125);auto *context=static_cast<shackcq_digi_context *>(m_continuousContext);m_dspInFlight=true;QPointer<AgentDigiController> self(this);
  m_dspFuture=QtConcurrent::run([self,display=std::move(display),continuous=std::move(continuous),mode,pitch,context,contextGeneration]{
    std::array<float,512> bins{};const int count=shackcq_digi_spectrum(display.constData(),display.size(),12'000,0,3'000,bins.size(),0,bins.data(),bins.size());QJsonArray row;for(int i=0;i<count;++i)row.append(std::clamp(int((bins[i]+120.0f)*2.0f),0,255));QString transcript;QJsonObject sstv;
    if(!continuous.isEmpty()&&QStringList{"CW","RTTY","PSK31","SSTV"}.contains(mode)){std::array<char,8192> output{};int n=mode=="CW"?shackcq_digi_feed_cw(context,continuous.constData(),continuous.size(),output.data(),output.size()):mode=="RTTY"?shackcq_digi_feed_rtty(context,continuous.constData(),continuous.size(),output.data(),output.size()):mode=="PSK31"?shackcq_digi_decode_psk31(continuous.constData(),continuous.size(),pitch,output.data(),output.size()):shackcq_digi_feed_sstv(context,continuous.constData(),continuous.size(),output.data(),output.size());if(n>0){if(mode=="SSTV"){sstv=QJsonDocument::fromJson(QByteArray(output.data(),n)).object();if(sstv.value("complete").toBool()){const int w=sstv.value("width").toInt(),h=sstv.value("height").toInt(),needed=context?shackcq_digi_copy_sstv_image(context,nullptr,0):-1;if(w>0&&h>0&&needed==w*h*3&&needed<=2'000'000){QByteArray rgb(needed,Qt::Uninitialized);if(shackcq_digi_copy_sstv_image(context,reinterpret_cast<uint8_t *>(rgb.data()),rgb.size())==needed){QImage image(reinterpret_cast<const uchar *>(rgb.constData()),w,h,w*3,QImage::Format_RGB888);QByteArray jpeg;for(const int quality:{70,55,40,25}){jpeg.clear();QBuffer buffer(&jpeg);buffer.open(QIODevice::WriteOnly);image.save(&buffer,"JPEG",quality);if(jpeg.size()<=18'000)break;}if(jpeg.size()<=18'000)sstv.insert("previewBase64",QString::fromLatin1(jpeg.toBase64()));else sstv.insert("previewOmitted","SNAPSHOT_SIZE_BOUND");}}}}else transcript=QString::fromUtf8(output.data(),n);}}
    if(!self)return;
    QMetaObject::invokeMethod(self,[self,row=std::move(row),transcript=std::move(transcript),sstv=std::move(sstv),contextGeneration]{if(!self)return;self->m_dspInFlight=false;if(self->m_contextGeneration!=contextGeneration)return;if(!row.isEmpty()){self->m_waterfall.append(QJsonObject{{"rowId",QUuid::createUuid().toString(QUuid::WithoutBraces)},{"sessionId",self->m_sessionId},{"observedUtc",QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs)},{"lowHz",0},{"highHz",3000},{"scale","DB_QUANTIZED_0_255"},{"bins",row}});while(self->m_waterfall.size()>8)self->m_waterfall.removeFirst();}if(!sstv.isEmpty())self->m_sstv=sstv;if(!transcript.isEmpty()){const QJsonObject retained{{"id",QUuid::createUuid().toString(QUuid::WithoutBraces)},{"slotStartMillis",QDateTime::currentMSecsSinceEpoch()},{"source","LIVE_CAPTURE"},{"exactSlotTiming",false},{"snr",0},{"dt",0},{"audioHz",self->m_rxAudioHz},{"text",transcript.left(512)}};self->m_decodes.prepend(retained);self->appendRetainedDecode(retained);while(self->m_decodes.size()>256)self->m_decodes.removeLast();}emit self->snapshotChanged();},Qt::QueuedConnection);
  });
#endif
}
QString AgentDigiController::stateName() const {switch(m_state){case State::Safe:return"SAFE";case State::Rx:return"RX";case State::Preparing:return"PREPARING";case State::Ready:return"READY_FOR_EXPLICIT_ARM";case State::Armed:return"ARMED_FOR_VALID_WINDOW";case State::PttConfirmed:return"PTT_CONFIRMED";case State::Transmitting:return"LOCAL_AUDIO_TRANSMITTING";case State::Stopping:return"STOPPING";case State::RxVerified:return"RX_VERIFIED";case State::RxUnconfirmed:return"RX_UNCONFIRMED";}return"SAFE";}

QJsonObject AgentDigiController::snapshot(const QString &agentId,
                                          const QString &deviceId,
                                          quint64 generation) {
  QJsonArray decodes;
  for (qsizetype i=0;i<std::min<qsizetype>(32,m_decodes.size());++i) decodes.append(m_decodes.at(i));
  QJsonArray waterfall;
  for (const auto &row : m_waterfall) waterfall.append(row);
  QJsonArray retainedSessions;
  for(const auto &value:m_retainedSessions){const QJsonObject row=value.toObject();retainedSessions.append(QJsonObject{{"id",row.value("id")},{"startedUtc",row.value("startedUtc")},{"endedUtc",row.value("endedUtc")},{"mode",row.value("mode")},{"submode",row.value("submode")},{"dialFrequencyHz",row.value("dialFrequencyHz")},{"sampleRate",row.value("sampleRate")},{"sampleCount",row.value("sampleCount")},{"byteSize",row.value("byteSize")},{"decodeCount",row.value("decodeCount")},{"recordingLocal",true}});}
  const bool leaseHeld = m_leaseExpiryMono > m_monotonic.elapsed();
  // Passive telemetry uses the radio controller's most recent polled
  // readback. A fresh synchronous helper request here can recursively enter
  // another cloud snapshot while the first radio poll is still unwinding.
  // Command, arm, transmit, and STOP paths continue to call pttReadback()
  // directly at their safety boundaries.
  const auto ptt = m_radio ? m_radio->transmitting() : std::nullopt;
  const bool freshRx=m_lastInputMono>0&&m_monotonic.elapsed()-m_lastInputMono<=2'000&&ptt&&!*ptt;
#if defined(SHACKCQ_HAVE_NATIVE_DIGI) && defined(SHACKCQ_HAVE_HAMLIB)
  constexpr bool transmitImplemented=true;
  const QJsonArray compiledModes{"FT8", "FT4", "FT2", "FST4", "Q65", "MSK144", "JT65", "WSPR", "CW", "RTTY", "PSK31", "SSTV"};
#else
  constexpr bool transmitImplemented=false;
  const QJsonArray compiledModes;
#endif
  const QJsonObject audio{{"state", m_audioState},
      {"inputDeviceId", m_profile.value("inputDeviceId").toString().isEmpty() ? QJsonValue::Null : QJsonValue(m_profile.value("inputDeviceId").toString())},
      {"outputDeviceId", m_profile.value("outputDeviceId").toString().isEmpty() ? QJsonValue::Null : QJsonValue(m_profile.value("outputDeviceId").toString())},
      {"sampleRate", m_sampleRate}, {"channels", m_channels}, {"rms", m_rms},
      {"peak", m_peak}, {"clipped", m_clipped}, {"detail", m_audioDetail}};
  const qint64 period=periodMillis(m_mode,m_submode),nextSlot=period?((QDateTime::currentMSecsSinceEpoch()/period)+1)*period:0;
  const QJsonObject clock{{"state", clockGood()?"GOOD":m_clockStartedMono>0?"DEGRADED":"UNKNOWN"},
      {"utcUncertaintyMs", m_clockUtcUncertaintyMs>=0?QJsonValue(m_clockUtcUncertaintyMs):QJsonValue::Null},
      {"sampleUncertaintyMs", m_clockSampleUncertaintyMs>=0?QJsonValue(m_clockSampleUncertaintyMs):QJsonValue::Null},
      {"nextSlotUtc", nextSlot>0?QJsonValue(QDateTime::fromMSecsSinceEpoch(nextSlot,QTimeZone::UTC).toString(Qt::ISODateWithMs)):QJsonValue::Null}};
  const QJsonObject lease{{"state", leaseHeld ? "HELD" : "NONE"},
      {"controlInstanceId", m_leaseControlInstance.isEmpty() ? QJsonValue::Null : QJsonValue(m_leaseControlInstance)},
      {"expiresUtc", leaseHeld ? QJsonValue(QDateTime::currentDateTimeUtc().addMSecs(m_leaseExpiryMono - m_monotonic.elapsed()).toString(Qt::ISODateWithMs)) : QJsonValue::Null}};
  const QJsonObject tx{{"implemented", transmitImplemented}, {"serverPermitted", m_serverTxPermitted},
      {"locallyPermitted", m_localTxPermitted}, {"hardwareAccepted", m_hardwareAccepted},
      {"armed", m_state == State::Armed}, {"transmitting", m_pttOwned||m_pttReleaseRequired},
      {"rxVerified", freshRx&&m_state!=State::RxUnconfirmed},
      {"detail", m_state == State::RxUnconfirmed ? "RX recovery is unconfirmed and latched" : "Dedicated Digi authority; generic PTT remains unavailable"}};
  const QJsonObject capabilities{{"modes", compiledModes},
      {"autoSequenceModes", QJsonArray{"FT8","FT4"}}, {"spectrumBins", 512},
      {"waterfallRowsPerSecond", 5}, {"recordingLocalOnly", true},
      {"retainedHistory", true}, {"retainedSessionLimit", MaximumRetainedSessions},
      {"companionAuthoritative", false}};
  return {{"type", "digi.snapshot"}, {"protocol", QJsonObject{{"major", 1}, {"minor", 2}}},
      {"agentId", agentId}, {"deviceId", deviceId},
      {"generation", QJsonValue::fromVariant(generation)},
      {"sequence", QJsonValue::fromVariant(++m_sequence)},
      {"observedUtc", QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs)},
      {"sessionId", m_sessionId.isEmpty() ? QJsonValue::Null : QJsonValue(m_sessionId)},
      {"contextGeneration", QJsonValue::fromVariant(m_contextGeneration)},
      {"state", stateName()}, {"mode", m_mode},
      {"submode", m_submode.isEmpty() ? QJsonValue::Null : QJsonValue(m_submode)},
      {"dialFrequencyHz", m_radio && m_radio->frequencyHz() ? QJsonValue::fromVariant(m_radio->frequencyHz()) : QJsonValue::Null},
      {"rxAudioHz", m_rxAudioHz}, {"txAudioHz", m_txAudioHz},
      {"audio", audio}, {"clock", clock}, {"lease", lease}, {"tx", tx},
      {"capabilities", capabilities}, {"decodes", decodes}, {"waterfall", waterfall}, {"retainedSessions",retainedSessions}, {"ftSequence",m_ftSequence}, {"sstv", m_sstv}};
}
} // namespace shackcq::desktop
