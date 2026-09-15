// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/DesktopRadioFleet.hpp"

#include <QDateTime>
#include <QRegularExpression>
#include <QSet>
#include <algorithm>
#include <utility>

namespace shackcq::desktop {
namespace {
constexpr int MaximumRadios = 8;
const QRegularExpression Identifier(QStringLiteral("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"));
}

struct DesktopRadioFleet::Entry {
  QString id, name, backend, route, nativeProfileId;
  int modelId{1}, baudRate{38400};
  DesktopRadioController *controller{};
  quint64 sequence{};
  ~Entry() { delete controller; }
};

DesktopRadioFleet::DesktopRadioFleet(QObject *parent) : QObject(parent) {}
DesktopRadioFleet::~DesktopRadioFleet() { stopAll(); qDeleteAll(m_entries); }
DesktopRadioFleet::Entry *DesktopRadioFleet::entry(const QString &id) const { return m_entries.value(id, nullptr); }
bool DesktopRadioFleet::contains(const QString &id) const { return m_entries.contains(id); }

bool DesktopRadioFleet::restoreConfiguration(const QVariantMap &section, QString *error) {
  if (section.value("schemaVersion", 1).toInt() > 1) { if(error)*error="radioProfiles schema is newer than supported schema 1"; return false; }
  const QVariantList profiles=section.value("profiles").toList();
  if(profiles.size()>MaximumRadios){if(error)*error="At most eight radio profiles are supported";return false;}
  QSet<QString> ids,routes;QList<Entry*> replacements;
  for(const QVariant &value:profiles){const QVariantMap profile=value.toMap();auto *item=new Entry;
    item->id=profile.value("id").toString().trimmed();item->name=profile.value("name").toString().trimmed();item->backend=profile.value("backend").toString().trimmed().toUpper();item->route=profile.value("route").toString().trimmed();item->nativeProfileId=profile.value("nativeProfileId").toString().trimmed();item->modelId=profile.value("modelId",1).toInt();item->baudRate=profile.value("baudRate",38400).toInt();
    const QString routeIdentity=item->backend+":"+item->route.toLower();const bool valid=Identifier.match(item->id).hasMatch()&&!ids.contains(item->id)&&!item->name.isEmpty()&&item->name.size()<=80&&QSet<QString>{"HAMLIB","NATIVE","TCI"}.contains(item->backend)&&!item->route.isEmpty()&&!routes.contains(routeIdentity)&&item->baudRate>=1200&&item->baudRate<=921600&&(item->backend!="HAMLIB"||item->modelId>0)&&(item->backend!="NATIVE"||Identifier.match(item->nativeProfileId).hasMatch());
    if(!valid){delete item;qDeleteAll(replacements);if(error)*error="Invalid or duplicate radio profile";return false;}
    item->controller=new DesktopRadioController;ids.insert(item->id);routes.insert(routeIdentity);replacements.push_back(item);
  }
  stopAll();qDeleteAll(m_entries);m_entries.clear();
  for(Entry *item:replacements){item->controller->setParent(this);connect(item->controller,&DesktopRadioController::snapshotChanged,this,&DesktopRadioFleet::snapshotChanged);m_entries.insert(item->id,item);}
  emit inventoryChanged();emit snapshotChanged();return true;
}

QVariantMap DesktopRadioFleet::configuration() const {QVariantList profiles;QStringList ids=m_entries.keys();std::sort(ids.begin(),ids.end());for(const QString &id:ids){const Entry *item=entry(id);profiles.push_back(QVariantMap{{"id",item->id},{"name",item->name},{"backend",item->backend},{"route",item->route},{"nativeProfileId",item->nativeProfileId},{"modelId",item->modelId},{"baudRate",item->baudRate},{"connected",false}});}return{{"schemaVersion",1},{"profiles",profiles}};}

QJsonObject DesktopRadioFleet::capabilities(const Entry *item){const QVariantMap value=item->controller->backendCapabilities();return{{"modelId",item->modelId},{"manufacturer",item->controller->manufacturer()},{"model",item->controller->model().isEmpty()?item->name:item->controller->model()},{"backend",item->backend.toLower()},{"frequencyRangesHz",QJsonArray::fromVariantList(value.value("frequencyRangesHz").toList())},{"modes",QJsonArray::fromStringList(value.value("modes").toStringList())},{"filtersHz",QJsonArray::fromVariantList(value.value("filtersHz").toList())},{"setters",QJsonArray::fromStringList(value.value("setters").toStringList())},{"meters",QJsonArray::fromStringList(value.value("meters").toStringList())},{"readOnlyTxState",true},{"rotatorEnvelope","DECLARED_NO_MOVEMENT"}};}
QVariantList DesktopRadioFleet::descriptors() const {QVariantList result;QStringList ids=m_entries.keys();std::sort(ids.begin(),ids.end());for(const QString &id:ids){const Entry *item=entry(id);result.push_back(QVariantMap{{"id",item->id},{"name",item->name},{"hamlibModelId",item->modelId},{"manufacturer",item->controller->manufacturer()},{"model",item->controller->model().isEmpty()?item->name:item->controller->model()},{"backend",item->backend},{"capabilities",capabilities(item).toVariantMap()}});}return result;}

bool DesktopRadioFleet::connectProfile(const QString &id,QString *error){Entry *item=entry(id);if(!item){if(error)*error="RADIO_NOT_FOUND";return false;}bool ok=false;if(item->backend=="HAMLIB")ok=item->controller->connectRadio(item->modelId,item->route,item->baudRate);else if(item->backend=="NATIVE")ok=item->controller->connectNativeProfile(item->nativeProfileId,item->route,item->baudRate);else {QVariantMap tci{{"id",item->id},{"name",item->name},{"url",item->route},{"autoConnect",false}};ok=item->controller->saveTciProfile(tci)&&item->controller->connectTciProfile(item->id);}if(!ok&&error)*error="RADIO_CONNECT_FAILED";if(ok)emit snapshotChanged();return ok;}
void DesktopRadioFleet::disconnectProfile(const QString &id){if(Entry *item=entry(id)){item->controller->globalStop();item->controller->disconnectRadio();emit snapshotChanged();}}
void DesktopRadioFleet::stopAll(){for(Entry *item:std::as_const(m_entries)){item->controller->globalStop();item->controller->disconnectRadio();}}

QJsonArray DesktopRadioFleet::snapshots(const QString &agentId,quint64 generation){QJsonArray rows;QStringList ids=m_entries.keys();std::sort(ids.begin(),ids.end());for(const QString &id:ids){Entry *item=entry(id);DesktopRadioController *radio=item->controller;QJsonObject row{{"type","radio.snapshot"},{"protocol",QJsonObject{{"major",1},{"minor",3}}},{"agentId",agentId},{"deviceId",item->id},{"generation",QJsonValue::fromVariant(generation)},{"sequence",QJsonValue::fromVariant(++item->sequence)},{"observedUtc",QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs)},{"connection",radio->state().startsWith("Connected")?"live":"offline"},{"frequencyHz",radio->frequencyHz()?QJsonValue::fromVariant(radio->frequencyHz()):QJsonValue::Null},{"mode",radio->mode().isEmpty()?QJsonValue::Null:QJsonValue(radio->mode())},{"filterHz",radio->filterHz()>0?QJsonValue(radio->filterHz()):QJsonValue::Null},{"meters",QJsonObject::fromVariantMap(radio->meters())},{"transmitting",radio->transmitting()?QJsonValue(*radio->transmitting()):QJsonValue::Null},{"capabilities",capabilities(item)}};const QVariantMap controls=radio->receiveControls();for(auto it=controls.cbegin();it!=controls.cend();++it)row.insert(it.key(),QJsonValue::fromVariant(it.value()));rows.push_back(row);}return rows;}

QJsonObject DesktopRadioFleet::processCommand(const QJsonObject &frame,const QString &agentId,quint64 generation){const QString commandId=frame.value("commandId").toString(),deviceId=frame.value("deviceId").toString();auto result=[&](bool ok,const QString &code){return QJsonObject{{"type","radio.command.result"},{"protocol",QJsonObject{{"major",1},{"minor",3}}},{"commandId",commandId},{"agentId",agentId},{"deviceId",deviceId},{"generation",QJsonValue::fromVariant(generation)},{"ok",ok},{"code",code}};};Entry *item=entry(deviceId);if(!item||frame.value("agentId").toString()!=agentId||frame.value("expectedGeneration").toVariant().toULongLong()!=generation)return result(false,"STALE_AGENT_GENERATION");DesktopRadioController *radio=item->controller;if(!radio->state().startsWith("Connected"))return result(false,"RADIO_OFFLINE");if(radio->radioOperationActive())return result(false,"RADIO_BUSY_RETRY_REQUIRED");const QString action=frame.value("action").toString();const QJsonObject p=frame.value("parameters").toObject();bool accepted=false;if(action=="radio.set.frequency"&&p.size()==1)accepted=radio->requestFrequency(p.value("frequencyHz").toVariant().toULongLong());else if(action=="radio.set.mode"&&p.size()==1)accepted=radio->requestMode(p.value("mode").toString());else if(action=="radio.set.filter"&&p.size()==1)accepted=radio->requestFilter(p.value("filterHz").toInt());else if(action=="radio.set.rfGain"||action=="radio.set.afGain"||action=="radio.set.squelch"||action=="radio.set.noiseBlanker"||action=="radio.set.notch"||action=="radio.set.noiseReduction"||action=="radio.set.agc"||action=="radio.set.rit")accepted=radio->requestReceiveControl(action,p.toVariantMap());else return result(false,"CAPABILITY_NOT_ADVERTISED");emit snapshotChanged();return result(accepted,accepted?"READBACK_PENDING":"READBACK_NOT_CONFIRMED");}

} // namespace shackcq::desktop
