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
#include <QJsonDocument>
#include <QImage>
#include <QMediaDevices>
#include <QPointer>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSet>
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
}

AgentDigiController::AgentDigiController(DesktopRadioController *radio, QObject *parent)
    : QObject(parent), m_radio(radio) {
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
    if (radioMutationBlocked() &&
        (!m_radio->state().startsWith("Connected") || m_radio->frequencyHz() == 0 ||
         (m_preparedFrequencyHz > 0 && (m_radio->frequencyHz()!=m_preparedFrequencyHz ||
          m_radio->mode().compare(m_preparedRadioMode,Qt::CaseInsensitive)!=0 ||
          m_radio->filterHz()!=m_preparedFilterHz)))) stop("radio context changed");
    emit snapshotChanged();
  });
}

AgentDigiController::~AgentDigiController() {
  stop("Agent Digi owner closed");
  m_dspFuture.waitForFinished();
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
  if (m_continuousContext) shackcq_digi_context_destroy(static_cast<shackcq_digi_context *>(m_continuousContext));
#endif
}

bool AgentDigiController::restoreConfiguration(const QVariantMap &section, QString *error) {
  static const QSet<QString> allowed{"schemaVersion","audioProfile","localTxPermitted","hardwareAccepted","acceptedRadioIdentity"};
  for (auto it=section.cbegin(); it!=section.cend(); ++it) if (!allowed.contains(it.key())) {
    if (error) *error="Digi configuration contains runtime or transmit state"; return false;
  }
  if (section.value("schemaVersion",1).toInt()!=1) { if(error)*error="Unsupported Digi configuration schema"; return false; }
  const QVariantMap profile=section.value("audioProfile").toMap();
  if (!profile.isEmpty()) {
    static const QSet<QString> audioKeys{"id","inputDeviceId","outputDeviceId","sampleRate","inputChannel","outputChannel"};
    for(auto it=profile.cbegin();it!=profile.cend();++it)if(!audioKeys.contains(it.key())){if(error)*error="Invalid Digi audio profile field";return false;}
    const int rate=profile.value("sampleRate").toInt();
    if(!boundedId(profile.value("id").toString())||profile.value("inputDeviceId").toString().size()>256||profile.value("outputDeviceId").toString().size()>256||rate<12'000||rate>192'000){if(error)*error="Invalid Digi audio profile";return false;}
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
  QJsonObject value{{"type","digi.command.result"},{"protocol",QJsonObject{{"major",1},{"minor",1}}},{"commandId",frame.value("commandId")},{"agentId",agentId},{"deviceId",deviceId},{"generation",QJsonValue::fromVariant(generation)},{"ok",ok},{"code",code}};
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
  if(action=="digi.stop"){stop("operator STOP");return result(frame,agentId,deviceId,generation,m_state!=State::RxUnconfirmed,m_state==State::RxUnconfirmed?"RX_UNCONFIRMED":"STOPPED_RX_VERIFIED");}
  if(!ownsLease(frame))return result(frame,agentId,deviceId,generation,false,"CONTROL_LEASE_REQUIRED");
  m_leaseExpiryMono=m_monotonic.elapsed()+LeaseMillis;
  if(action=="digi.control.renew"){emit snapshotChanged();return result(frame,agentId,deviceId,generation,true,"CONTROL_RENEWED");}
  if(action=="digi.control.release"){stop("control released");m_leaseExpiryMono=0;m_leaseBrowserSession.clear();m_leaseControlInstance.clear();return result(frame,agentId,deviceId,generation,true,"CONTROL_RELEASED");}
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
    m_mode=mode;m_submode=submode;m_modeIndex=index;m_rxAudioHz=float(rx);m_txAudioHz=float(tx);m_continuousSamples.clear();m_sstv={};m_contextGeneration++;resetPrepared();emit snapshotChanged();return result(frame,agentId,deviceId,generation,true,"DIGI_CONFIGURED");
  }
  if(action=="digi.rx.start"){
    QString error;if(!configureAudio(p.value("audioProfileId").toString(),&error)||!startRx(&error))return result(frame,agentId,deviceId,generation,false,error);
    return result(frame,agentId,deviceId,generation,true,"RX_STARTED");
  }
  if(action=="digi.rx.stop"){
    stop("RX stop requested");
    if(m_state==State::RxUnconfirmed)return result(frame,agentId,deviceId,generation,false,"RX_UNCONFIRMED");
    if(m_source){m_source->stop();m_source->deleteLater();m_source=nullptr;m_input=nullptr;}
    m_state=State::Safe;m_audioState="STOPPED";m_lastInputMono=0;emit snapshotChanged();return result(frame,agentId,deviceId,generation,true,"RX_STOPPED");
  }
  if(action=="digi.select"){m_selectedDecode=p.value("decodeId").toString();emit snapshotChanged();return result(frame,agentId,deviceId,generation,true,"DECODE_SELECTED_NO_TUNE_NO_TX");}
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

bool AgentDigiController::startRx(QString *error){QAudioDevice input;for(const auto &d:QMediaDevices::audioInputs())if(audioId(d)==m_profile.value("inputDeviceId").toString())input=d;if(input.isNull()){if(error)*error="AUDIO_INPUT_MISSING";m_audioState="ROUTE_LOST";return false;}QAudioFormat f;f.setSampleRate(m_sampleRate);f.setChannelCount(1);f.setSampleFormat(QAudioFormat::Int16);if(!input.isFormatSupported(f)){if(error)*error="AUDIO_FORMAT_UNSUPPORTED";m_audioState="ERROR";return false;}if(m_source){m_source->stop();m_source->deleteLater();}m_source=new QAudioSource(input,f,this);m_input=m_source->start();if(!m_input){if(error)*error="AUDIO_OPEN_FAILED";return false;}connect(m_input,&QIODevice::readyRead,this,&AgentDigiController::consumeInput);m_sessionId=QUuid::createUuid().toString(QUuid::WithoutBraces);m_capture.clear();m_captureSlotStart=0;m_state=State::Rx;m_audioState="LIVE";m_audioDetail="Explicit local input active; DSP remains local";emit snapshotChanged();return true;}

void AgentDigiController::consumeInput() {
  if (!m_input) return;
  const QByteArray bytes = m_input->read(256 * 1024);
  const qsizetype count = bytes.size() / 2;
  if (count <= 0) return;
  const auto *pcm = reinterpret_cast<const qint16 *>(bytes.constData());
  double sum = 0;
  float peak = 0;
  for (qsizetype i = 0; i < count; ++i) {
    const float value = float(qFromLittleEndian<qint16>(pcm + i)) / 32768.0f;
    sum += double(value) * value;
    peak = std::max(peak, std::abs(value));
    m_resamplePhase += 12'000.0 / double(m_sampleRate);
    if (m_resamplePhase >= 1.0) {
      if (m_modeIndex >= 0) m_capture.push_back(value);
      m_displaySamples.push_back(value);
      if (QStringList{"CW","RTTY","PSK31","SSTV"}.contains(m_mode)) m_continuousSamples.push_back(value);
      m_resamplePhase -= 1.0;
    }
  }
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
    const qint64 completedSlot = m_captureSlotStart;
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
          self->m_decodes.prepend(QJsonObject{{"id", QUuid::createUuid().toString(QUuid::WithoutBraces)},
            {"slotStartMillis", completedSlot}, {"source", "LIVE_CAPTURE"}, {"exactSlotTiming", false},
            {"snr", row.value("snr")}, {"dt", row.value("dt")},
            {"audioHz", row.value("frequencyHz")}, {"text", row.value("text").toString().left(512)}});
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
void AgentDigiController::finishTransmit(){if(m_sink){m_sink->reset();m_sink->deleteLater();m_sink=nullptr;}m_output.close();m_state=State::Stopping;const bool released=!m_pttReleaseRequired||setPtt(false);const auto rx=pttReadback();const bool safe=released&&rx&&!*rx;m_pttOwned=false;m_pttReleaseRequired=!safe;m_state=safe?State::RxVerified:State::RxUnconfirmed;if(m_state==State::RxVerified&&m_repeatsRemaining>0&&m_armExpiryMono>m_monotonic.elapsed()){m_state=State::Armed;QString error;if(scheduleTransmit(&error)){emit snapshotChanged();return;}}resetPrepared();emit snapshotChanged();}
void AgentDigiController::stop(const QString &reason){Q_UNUSED(reason);++m_sendIntentGeneration;m_sendScheduled=false;m_contextGeneration++;m_scanner.stop();m_scannerEntries={};m_scannerRemaining=0;m_txFinish.stop();if(m_sink){m_sink->reset();m_sink->deleteLater();m_sink=nullptr;}m_output.close();if(m_pttReleaseRequired){m_state=State::Stopping;const bool released=setPtt(false);const auto rx=pttReadback();const bool safe=released&&rx&&!*rx;m_pttOwned=false;m_pttReleaseRequired=!safe;m_state=safe?State::RxVerified:State::RxUnconfirmed;}else if(m_state!=State::RxUnconfirmed)m_state=m_source?State::Rx:State::Safe;resetPrepared();emit snapshotChanged();}
void AgentDigiController::resetPrepared(){m_txPcm.clear();m_preparedMessage.clear();m_preparedFrequencyHz=0;m_preparedRadioMode.clear();m_preparedFilterHz=0;m_armExpiryMono=0;m_nextSlotEpoch=0;m_repeatsRemaining=0;}
void AgentDigiController::expireLease(){if(m_leaseExpiryMono>0&&m_leaseExpiryMono<=m_monotonic.elapsed()){stop("control lease expired");m_leaseExpiryMono=0;m_leaseBrowserSession.clear();m_leaseControlInstance.clear();}if(m_armExpiryMono>0&&m_armExpiryMono<=m_monotonic.elapsed()&&m_state==State::Armed){m_state=State::Ready;m_armExpiryMono=0;emit snapshotChanged();}if(m_state==State::Rx&&m_lastInputMono>0&&m_monotonic.elapsed()-m_lastInputMono>2'000){m_audioState="SILENT";m_audioDetail="No fresh samples from selected input";emit snapshotChanged();}processDisplayAndContinuous();}

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

void AgentDigiController::processDisplayAndContinuous(){
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
  if(m_dspInFlight||m_displaySamples.size()<2048)return;
  const qsizetype displayStart=std::max<qsizetype>(0,m_displaySamples.size()-4096);
  QVector<float> display=m_displaySamples.sliced(displayStart);m_displaySamples.clear();QVector<float> continuous;continuous.swap(m_continuousSamples);const QString mode=m_mode;const float pitch=m_rxAudioHz;const quint64 contextGeneration=m_contextGeneration;if(!m_continuousContext&&QStringList{"CW","RTTY","SSTV"}.contains(mode))m_continuousContext=shackcq_digi_context_create(12'000,pitch,false,2125);auto *context=static_cast<shackcq_digi_context *>(m_continuousContext);m_dspInFlight=true;QPointer<AgentDigiController> self(this);
  m_dspFuture=QtConcurrent::run([self,display=std::move(display),continuous=std::move(continuous),mode,pitch,context,contextGeneration]{
    std::array<float,512> bins{};const int count=shackcq_digi_spectrum(display.constData(),display.size(),12'000,0,3'000,bins.size(),0,bins.data(),bins.size());QJsonArray row;for(int i=0;i<count;++i)row.append(std::clamp(int((bins[i]+120.0f)*2.0f),0,255));QString transcript;QJsonObject sstv;
    if(!continuous.isEmpty()&&QStringList{"CW","RTTY","PSK31","SSTV"}.contains(mode)){std::array<char,8192> output{};int n=mode=="CW"?shackcq_digi_feed_cw(context,continuous.constData(),continuous.size(),output.data(),output.size()):mode=="RTTY"?shackcq_digi_feed_rtty(context,continuous.constData(),continuous.size(),output.data(),output.size()):mode=="PSK31"?shackcq_digi_decode_psk31(continuous.constData(),continuous.size(),pitch,output.data(),output.size()):shackcq_digi_feed_sstv(context,continuous.constData(),continuous.size(),output.data(),output.size());if(n>0){if(mode=="SSTV"){sstv=QJsonDocument::fromJson(QByteArray(output.data(),n)).object();if(sstv.value("complete").toBool()){const int w=sstv.value("width").toInt(),h=sstv.value("height").toInt(),needed=context?shackcq_digi_copy_sstv_image(context,nullptr,0):-1;if(w>0&&h>0&&needed==w*h*3&&needed<=2'000'000){QByteArray rgb(needed,Qt::Uninitialized);if(shackcq_digi_copy_sstv_image(context,reinterpret_cast<uint8_t *>(rgb.data()),rgb.size())==needed){QImage image(reinterpret_cast<const uchar *>(rgb.constData()),w,h,w*3,QImage::Format_RGB888);QByteArray jpeg;for(const int quality:{70,55,40,25}){jpeg.clear();QBuffer buffer(&jpeg);buffer.open(QIODevice::WriteOnly);image.save(&buffer,"JPEG",quality);if(jpeg.size()<=18'000)break;}if(jpeg.size()<=18'000)sstv.insert("previewBase64",QString::fromLatin1(jpeg.toBase64()));else sstv.insert("previewOmitted","SNAPSHOT_SIZE_BOUND");}}}}else transcript=QString::fromUtf8(output.data(),n);}}
    if(!self)return;
    QMetaObject::invokeMethod(self,[self,row=std::move(row),transcript=std::move(transcript),sstv=std::move(sstv),contextGeneration]{if(!self)return;self->m_dspInFlight=false;if(self->m_contextGeneration!=contextGeneration)return;if(!row.isEmpty()){self->m_waterfall.append(QJsonObject{{"rowId",QUuid::createUuid().toString(QUuid::WithoutBraces)},{"sessionId",self->m_sessionId},{"observedUtc",QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs)},{"lowHz",0},{"highHz",3000},{"scale","DB_QUANTIZED_0_255"},{"bins",row}});while(self->m_waterfall.size()>8)self->m_waterfall.removeFirst();}if(!sstv.isEmpty())self->m_sstv=sstv;if(!transcript.isEmpty()){self->m_decodes.prepend(QJsonObject{{"id",QUuid::createUuid().toString(QUuid::WithoutBraces)},{"slotStartMillis",QDateTime::currentMSecsSinceEpoch()},{"source","LIVE_CAPTURE"},{"exactSlotTiming",false},{"snr",0},{"dt",0},{"audioHz",self->m_rxAudioHz},{"text",transcript.left(512)}});while(self->m_decodes.size()>256)self->m_decodes.removeLast();}emit self->snapshotChanged();},Qt::QueuedConnection);
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
  const bool leaseHeld = m_leaseExpiryMono > m_monotonic.elapsed();
  const auto ptt=pttReadback();
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
  const QJsonObject clock{{"state", "UNKNOWN"},
      {"utcUncertaintyMs", QJsonValue::Null},
      {"sampleUncertaintyMs", QJsonValue::Null},
      {"nextSlotUtc", QJsonValue::Null}};
  const QJsonObject lease{{"state", leaseHeld ? "HELD" : "NONE"},
      {"controlInstanceId", m_leaseControlInstance.isEmpty() ? QJsonValue::Null : QJsonValue(m_leaseControlInstance)},
      {"expiresUtc", leaseHeld ? QJsonValue(QDateTime::currentDateTimeUtc().addMSecs(m_leaseExpiryMono - m_monotonic.elapsed()).toString(Qt::ISODateWithMs)) : QJsonValue::Null}};
  const QJsonObject tx{{"implemented", transmitImplemented}, {"serverPermitted", m_serverTxPermitted},
      {"locallyPermitted", m_localTxPermitted}, {"hardwareAccepted", m_hardwareAccepted},
      {"armed", m_state == State::Armed}, {"transmitting", m_pttOwned||m_pttReleaseRequired},
      {"rxVerified", freshRx&&m_state!=State::RxUnconfirmed},
      {"detail", m_state == State::RxUnconfirmed ? "RX recovery is unconfirmed and latched" : "Dedicated Digi authority; generic PTT remains unavailable"}};
  const QJsonObject capabilities{{"modes", compiledModes},
      {"autoSequenceModes", QJsonArray{}}, {"spectrumBins", 512},
      {"waterfallRowsPerSecond", 5}, {"recordingLocalOnly", true},
      {"companionAuthoritative", false}};
  return {{"type", "digi.snapshot"}, {"protocol", QJsonObject{{"major", 1}, {"minor", 1}}},
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
      {"capabilities", capabilities}, {"decodes", decodes}, {"waterfall", waterfall}, {"sstv", m_sstv}};
}
} // namespace shackcq::desktop
