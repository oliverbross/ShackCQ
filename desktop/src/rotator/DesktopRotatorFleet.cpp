// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/DesktopRotatorFleet.hpp"

#include <QDateTime>
#include <QJsonArray>
#include <QRegularExpression>
#include <QSet>
#include <QUuid>
#include <algorithm>
#include <cmath>
#include <utility>

namespace shackcq::desktop {
namespace {
constexpr int MaximumRotators = 8;
constexpr qint64 PreparationLifetimeMs = 15'000;
constexpr qint64 ControlLeaseLifetimeMs = 10'000;
const QRegularExpression Identifier(QStringLiteral("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"));

bool number(const QJsonValue &value, double minimum, double maximum) {
  return value.isDouble() && std::isfinite(value.toDouble()) &&
         value.toDouble() >= minimum && value.toDouble() <= maximum;
}

bool boundedIdentifiers(const QVariantList &values, int maximum) {
  if (values.size() > maximum)
    return false;
  QSet<QString> seen;
  for (const QVariant &value : values) {
    const QString id = value.toString();
    if (!Identifier.match(id).hasMatch() || seen.contains(id))
      return false;
    seen.insert(id);
  }
  return true;
}
} // namespace

struct DesktopRotatorFleet::Entry {
  QString id;
  QString name;
  QString backend;
  QString model;
  QString protocol;
  QString route;
  int modelId{};
  int baudRate{9600};
  double minimumAzimuth{};
  double maximumAzimuth{450};
  double minimumElevation{-10};
  double maximumElevation{180};
  QVariantList forbiddenSectors;
  QVariantList antennas;
  QVariantList radioIds;
  QVariantList bands;
  QString automationPolicy{"MANUAL"};
  DesktopRotatorController *controller{};
  quint64 generation{1};
  quint64 sequence{};
  QString preparationId;
  qint64 preparationExpiry{};
  QString controlInstanceId;
  qint64 controlLeaseExpiry{};
  QTimer jogDeadman;
  ~Entry() { delete controller; }
};

DesktopRotatorFleet::DesktopRotatorFleet(QObject *parent) : QObject(parent) {
  m_clock.start();
}

DesktopRotatorFleet::~DesktopRotatorFleet() {
  stopAll();
  qDeleteAll(m_entries);
}

DesktopRotatorFleet::Entry *DesktopRotatorFleet::entry(const QString &id) const {
  return m_entries.value(id, nullptr);
}

bool DesktopRotatorFleet::restoreConfiguration(const QVariantMap &section,
                                               QString *error) {
  if (section.value("schemaVersion", 3).toInt() > 3) {
    if (error) *error = "rotatorProfiles schema is newer than supported schema 3";
    return false;
  }
  const QVariantList profiles = section.value("profiles").toList();
  if (profiles.size() > MaximumRotators) {
    if (error) *error = "At most eight rotator profiles are supported";
    return false;
  }
  QSet<QString> ids, routes;
  QList<Entry *> replacements;
  for (const QVariant &value : profiles) {
    const QVariantMap profile = value.toMap();
    auto *item = new Entry;
    item->id = profile.value("id").toString().trimmed();
    item->name = profile.value("name").toString().trimmed();
    item->backend = profile.value("backend").toString().trimmed().toUpper();
    item->model = profile.value("model").toString().trimmed();
    item->protocol = profile.value("protocol").toString().trimmed().toUpper();
    item->route = profile.value("route").toString().trimmed();
    item->modelId = profile.value("modelId").toInt();
    item->baudRate = profile.value("baudRate", 9600).toInt();
    item->minimumAzimuth = profile.value("minimumAzimuth", 0).toDouble();
    item->maximumAzimuth = profile.value("maximumAzimuth", 450).toDouble();
    item->minimumElevation = profile.value("minimumElevation", -10).toDouble();
    item->maximumElevation = profile.value("maximumElevation", 180).toDouble();
    item->forbiddenSectors = profile.value("forbiddenSectors").toList();
    item->antennas = profile.value("antennas").toList();
    item->radioIds = profile.value("radioIds").toList();
    item->bands = profile.value("bands").toList();
    item->automationPolicy = profile.value("automationPolicy", "MANUAL").toString().trimmed().toUpper();
    const QString routeIdentity = item->route.toLower();
    const bool valid = Identifier.match(item->id).hasMatch() && !ids.contains(item->id) &&
        !item->name.isEmpty() && item->name.size() <= 80 &&
        QSet<QString>{"HAMLIB", "GS232", "EASYCOMM", "ROTCTLD"}.contains(item->backend) &&
        !item->route.isEmpty() && !routes.contains(routeIdentity) && item->baudRate >= 1200 && item->baudRate <= 921600 &&
        (item->backend != "HAMLIB" || item->modelId > 0) &&
        boundedIdentifiers(item->antennas, 16) &&
        boundedIdentifiers(item->radioIds, 8) &&
        boundedIdentifiers(item->bands, 64) &&
        QSet<QString>{"OFF", "MANUAL", "PROMPT", "AUTO_SELECTED_TARGET", "SATELLITE_SESSION"}.contains(item->automationPolicy);
    item->controller = new DesktopRotatorController;
    if (!valid || !item->controller->configureSafety(item->minimumAzimuth, item->maximumAzimuth, item->minimumElevation, item->maximumElevation, item->forbiddenSectors)) {
      delete item;
      qDeleteAll(replacements);
      if (error) *error = "Invalid, duplicate or unsafe rotator profile";
      return false;
    }
    ids.insert(item->id); routes.insert(routeIdentity); replacements.push_back(item);
  }
  stopAll(); qDeleteAll(m_entries); m_entries.clear();
  for (Entry *item : replacements) {
    item->controller->setParent(this);
    connect(item->controller, &DesktopRotatorController::snapshotChanged, this, &DesktopRotatorFleet::snapshotChanged);
    connect(item->controller, &DesktopRotatorController::preparedChanged, this, &DesktopRotatorFleet::snapshotChanged);
    item->jogDeadman.setSingleShot(true);
    connect(&item->jogDeadman, &QTimer::timeout, this, [this, item] {
      item->controller->stop();
      item->controlInstanceId.clear();
      item->controlLeaseExpiry = 0;
      emit snapshotChanged();
    });
    m_entries.insert(item->id, item);
  }
  emit snapshotChanged();
  return true;
}

QVariantMap DesktopRotatorFleet::configuration() const {
  QVariantList profiles;
  QStringList ids = m_entries.keys(); std::sort(ids.begin(), ids.end());
  for (const QString &id : ids) {
    const Entry *item = m_entries.value(id);
    profiles.push_back(QVariantMap{{"id",item->id},{"name",item->name},{"backend",item->backend},{"model",item->model},{"protocol",item->protocol},{"route",item->route},{"modelId",item->modelId},{"baudRate",item->baudRate},{"minimumAzimuth",item->minimumAzimuth},{"maximumAzimuth",item->maximumAzimuth},{"minimumElevation",item->minimumElevation},{"maximumElevation",item->maximumElevation},{"forbiddenSectors",item->forbiddenSectors},{"antennas",item->antennas},{"radioIds",item->radioIds},{"bands",item->bands},{"automationPolicy",item->automationPolicy},{"connected",false},{"moving",false},{"pendingTarget",QVariant{}}});
  }
  return {{"schemaVersion",3},{"profiles",profiles}};
}

QVariantList DesktopRotatorFleet::descriptors() const {
  QVariantList result;
  QStringList ids=m_entries.keys();std::sort(ids.begin(),ids.end());
  for(const QString &id:ids){const Entry *item=m_entries.value(id);const bool manualJog=item->backend!="EASYCOMM";result.push_back(QVariantMap{{"id",item->id},{"name",item->name},{"backend",item->backend},{"model",item->model},{"antennas",item->antennas},{"radioIds",item->radioIds},{"bands",item->bands},{"automationPolicy",item->automationPolicy},{"capabilities",QVariantMap{{"backend",item->backend},{"model",item->model},{"azimuth",true},{"elevation",item->maximumElevation>item->minimumElevation},{"manualJog",manualJog},{"park",item->backend=="HAMLIB"},{"stop",true},{"minimumAzimuth",item->minimumAzimuth},{"maximumAzimuth",item->maximumAzimuth},{"minimumElevation",item->minimumElevation},{"maximumElevation",item->maximumElevation}}}});}
  return result;
}

bool DesktopRotatorFleet::connectProfile(const QString &id, QString *error) {
  Entry *item=entry(id);if(!item){if(error)*error="ROTATOR_NOT_FOUND";return false;}
  const bool ok=item->backend=="HAMLIB"?item->controller->connectRotator(item->modelId,item->route,item->baudRate):item->controller->connectNative(item->backend,item->route,item->baudRate);
  if(!ok){if(error)*error="ROTATOR_CONNECT_FAILED";return false;}++item->generation;emit snapshotChanged();return true;
}

void DesktopRotatorFleet::disconnectProfile(const QString &id){if(Entry *item=entry(id)){item->jogDeadman.stop();item->controller->stop();item->controller->disconnectRotator();item->preparationId.clear();item->controlInstanceId.clear();++item->generation;emit snapshotChanged();}}
void DesktopRotatorFleet::stopAll(){for(Entry *item:std::as_const(m_entries)){item->jogDeadman.stop();item->controller->stop();item->preparationId.clear();item->controlInstanceId.clear();item->controlLeaseExpiry=0;}}

QJsonObject DesktopRotatorFleet::snapshot(Entry *item,const QString &agentId,quint64 agentGeneration){
  const bool connected=item->controller->protocol()!="none",fresh=item->controller->positionObserved();
  const QJsonObject capabilities{{"backend",item->backend},{"model",item->model},{"azimuth",true},{"elevation",item->maximumElevation>item->minimumElevation},{"manualJog",item->backend!="EASYCOMM"},{"park",item->backend=="HAMLIB"},{"stop",true},{"minimumAzimuth",item->minimumAzimuth},{"maximumAzimuth",item->maximumAzimuth},{"minimumElevation",item->minimumElevation},{"maximumElevation",item->maximumElevation}};
  return {{"type","rotator.snapshot"},{"protocol",QJsonObject{{"major",1},{"minor",1}}},{"agentId",agentId},{"deviceId",item->id},{"generation",QJsonValue::fromVariant(agentGeneration)},{"sequence",QJsonValue::fromVariant(++item->sequence)},{"observedUtc",QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs)},{"connection",connected?(fresh?"live":"stale"):"offline"},{"state",item->jogDeadman.isActive()?"MOVING":(item->preparationId.isEmpty()?"IDLE":"PREPARED")},{"azimuth",fresh?QJsonValue(item->controller->azimuth()):QJsonValue::Null},{"elevation",fresh?QJsonValue(item->controller->elevation()):QJsonValue::Null},{"targetAzimuth",item->preparationId.isEmpty()?QJsonValue::Null:QJsonValue(item->controller->preparedAzimuth())},{"targetElevation",item->preparationId.isEmpty()?QJsonValue::Null:QJsonValue(item->controller->preparedElevation())},{"positionFresh",fresh},{"selectedAntenna",item->antennas.isEmpty()?QJsonValue::Null:QJsonValue::fromVariant(item->antennas.front())},{"antennas",QJsonArray::fromVariantList(item->antennas)},{"radioIds",QJsonArray::fromVariantList(item->radioIds)},{"bands",QJsonArray::fromVariantList(item->bands)},{"automationPolicy",item->automationPolicy},{"capabilities",capabilities}};
}

QJsonArray DesktopRotatorFleet::snapshots(const QString &agentId,quint64 generation){QJsonArray rows;QStringList ids=m_entries.keys();std::sort(ids.begin(),ids.end());for(const QString &id:ids)rows.push_back(snapshot(m_entries.value(id),agentId,generation));return rows;}

QJsonObject DesktopRotatorFleet::processCommand(const QJsonObject &frame,const QString &agentId,quint64 generation){
  const QString commandId=frame.value("commandId").toString(),deviceId=frame.value("deviceId").toString(),action=frame.value("action").toString();
  auto result=[&](bool ok,const QString &code){return QJsonObject{{"type","rotator.command.result"},{"protocol",QJsonObject{{"major",1},{"minor",1}}},{"commandId",commandId},{"agentId",agentId},{"deviceId",deviceId},{"generation",QJsonValue::fromVariant(generation)},{"ok",ok},{"code",code}};};
  Entry *item=entry(deviceId);if(!item||frame.value("agentId").toString()!=agentId||frame.value("expectedGeneration").toVariant().toULongLong()!=generation)return result(false,"ROTATOR_COMMAND_SCOPE_REJECTED");
  const QDateTime deadline=QDateTime::fromString(frame.value("validUntilUtc").toString(),Qt::ISODate);if(!deadline.isValid()||deadline<QDateTime::currentDateTimeUtc())return result(false,"ROTATOR_COMMAND_EXPIRED");
  const QJsonObject parameters=frame.value("parameters").toObject();
  const QString controlInstanceId=frame.value("controlInstanceId").toString();
  const bool stopping=action=="rotator.stop"||action=="rotator.jog.stop";
  if(!stopping){
    if(!Identifier.match(controlInstanceId).hasMatch())return result(false,"ROTATOR_CONTROL_LEASE_REQUIRED");
    if(item->controlLeaseExpiry>m_clock.elapsed()&&!item->controlInstanceId.isEmpty()&&item->controlInstanceId!=controlInstanceId)return result(false,"ROTATOR_CONTROL_LEASE_BUSY");
    item->controlInstanceId=controlInstanceId;item->controlLeaseExpiry=m_clock.elapsed()+ControlLeaseLifetimeMs;
  }
  if(stopping){item->jogDeadman.stop();item->controller->stop();item->preparationId.clear();item->controlInstanceId.clear();item->controlLeaseExpiry=0;emit snapshotChanged();return result(true,item->controller->positionObserved()?"STOP_REQUESTED_POSITION_PENDING":"STOP_REQUESTED_POSITION_UNKNOWN");}
  if(item->controller->protocol()=="none")return result(false,"ROTATOR_OFFLINE");
  if(action=="rotator.target.prepare"){
    if(!number(parameters.value("azimuth"),-180,720)||!number(parameters.value("elevation"),-90,180)||!item->controller->prepareTarget(parameters.value("azimuth").toDouble(),parameters.value("elevation").toDouble()))return result(false,"ROTATOR_TARGET_REJECTED");
    item->preparationId=QUuid::createUuid().toString(QUuid::WithoutBraces);item->preparationExpiry=m_clock.elapsed()+PreparationLifetimeMs;QJsonObject value=result(true,"ROTATOR_TARGET_PREPARED");value.insert("preparationId",item->preparationId);emit snapshotChanged();return value;
  }
  if(action=="rotator.move.confirm"){
    if(item->preparationId.isEmpty()||parameters.value("preparationId").toString()!=item->preparationId||m_clock.elapsed()>item->preparationExpiry){item->preparationId.clear();return result(false,"ROTATOR_PREPARATION_EXPIRED");}
    item->preparationId.clear();const bool ok=item->controller->confirmMove();emit snapshotChanged();return result(ok,ok?"ROTATOR_MOVE_DISPATCHED_READBACK_PENDING":"ROTATOR_MOVE_REJECTED");
  }
  if(action=="rotator.park"){const bool ok=item->controller->park();return result(ok,ok?"ROTATOR_PARK_DISPATCHED_READBACK_PENDING":"ROTATOR_PARK_UNAVAILABLE");}
  if(action=="rotator.jog.start"){
    const QString direction=parameters.value("direction").toString().toUpper();const int speed=parameters.value("speed").toInt(),deadmanMs=parameters.value("deadmanMs").toInt();
    if(!QSet<QString>{"UP","DOWN","LEFT","RIGHT"}.contains(direction)||speed<1||speed>100||deadmanMs<100||deadmanMs>1000)return result(false,"ROTATOR_JOG_REJECTED");
    if((direction=="UP"||direction=="DOWN")&&item->maximumElevation<=item->minimumElevation)return result(false,"ROTATOR_JOG_UNSUPPORTED");
    const bool ok=item->controller->jog(direction,speed);if(ok)item->jogDeadman.start(deadmanMs);emit snapshotChanged();return result(ok,ok?"ROTATOR_JOG_STARTED_DEADMAN_ARMED":"ROTATOR_JOG_UNAVAILABLE");
  }
  return result(false,"ROTATOR_ACTION_UNSUPPORTED");
}

} // namespace shackcq::desktop
