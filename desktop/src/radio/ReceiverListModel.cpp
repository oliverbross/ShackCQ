#include "shackcq/desktop/ReceiverListModel.hpp"

namespace shackcq::desktop {

ReceiverListModel::ReceiverListModel(QObject *parent)
    : QAbstractListModel(parent) {}

int ReceiverListModel::rowCount(const QModelIndex &parent) const {
  return parent.isValid() ? 0 : m_rows.size();
}

QVariant ReceiverListModel::data(const QModelIndex &index, int role) const {
  if (!index.isValid() || index.row() < 0 || index.row() >= m_rows.size())
    return {};
  const QVariantMap row = m_rows.at(index.row()).toMap();
  static const QHash<int, QString> keys{
      {IdRole, "id"},
      {LabelRole, "label"},
      {BackendIndexRole, "backendIndex"},
      {EnabledRole, "enabled"},
      {MutedRole, "muted"},
      {ActiveControlRole, "activeControl"},
      {ActiveListeningRole, "activeListening"},
      {TransmitOwnerRole, "transmitOwner"},
      {CentreFrequencyRole, "centreFrequencyHz"},
      {VfoARole, "vfoAHz"},
      {VfoBRole, "vfoBHz"},
      {SelectedChannelRole, "selectedChannel"},
      {EffectiveReceiveRole, "effectiveReceiveHz"},
      {ModeRole, "mode"},
      {FilterLowRole, "filterLowHz"},
      {FilterHighRole, "filterHighHz"},
      {SampleRateRole, "sampleRate"},
      {IqStateRole, "iqState"},
      {AudioStateRole, "audioState"},
      {SignalDbmRole, "signalDbm"},
      {ForwardPowerRole, "forwardPowerW"},
      {SwrRole, "swr"},
      {LastObservedRole, "lastObservedMs"},
      {DroppedFramesRole, "droppedIqFrames"},
      {StaleRole, "stale"},
      {ErrorRole, "error"}};
  return row.value(keys.value(role));
}

QHash<int, QByteArray> ReceiverListModel::roleNames() const {
  return {{IdRole, "receiverId"},
          {LabelRole, "displayLabel"},
          {BackendIndexRole, "backendIndex"},
          {EnabledRole, "enabled"},
          {MutedRole, "muted"},
          {ActiveControlRole, "activeControl"},
          {ActiveListeningRole, "activeListening"},
          {TransmitOwnerRole, "transmitOwner"},
          {CentreFrequencyRole, "centreFrequencyHz"},
          {VfoARole, "vfoAHz"},
          {VfoBRole, "vfoBHz"},
          {SelectedChannelRole, "selectedChannel"},
          {EffectiveReceiveRole, "effectiveReceiveHz"},
          {ModeRole, "mode"},
          {FilterLowRole, "filterLowHz"},
          {FilterHighRole, "filterHighHz"},
          {SampleRateRole, "sampleRate"},
          {IqStateRole, "iqState"},
          {AudioStateRole, "audioState"},
          {SignalDbmRole, "signalDbm"},
          {ForwardPowerRole, "forwardPowerW"},
          {SwrRole, "swr"},
          {LastObservedRole, "lastObservedMs"},
          {DroppedFramesRole, "droppedFrames"},
          {StaleRole, "stale"},
          {ErrorRole, "receiverError"}};
}

QVariantMap ReceiverListModel::receiver(const QString &id) const {
  for (const QVariant &entry : m_rows) {
    const QVariantMap row = entry.toMap();
    if (row.value("id").toString() == id)
      return row;
  }
  return {};
}

void ReceiverListModel::replace(QVariantList rows, const QString &activeId,
                                const QString &listeningId,
                                const QString &transmitId) {
  for (QVariant &entry : rows) {
    QVariantMap row = entry.toMap();
    const QString id = row.value("id").toString();
    row["activeControl"] = id == activeId;
    row["activeListening"] = id == listeningId;
    row["transmitOwner"] = id == transmitId;
    if (!row.contains("filterLowHz"))
      row["filterLowHz"] = 0;
    if (!row.contains("filterHighHz"))
      row["filterHighHz"] = 0;
    if (!row.contains("signalDbm"))
      row["signalDbm"] = 0.0;
    entry = row;
  }
  const int previousCount = m_rows.size();
  beginResetModel();
  m_rows = std::move(rows);
  endResetModel();
  if (previousCount != m_rows.size())
    emit countChanged();
}

void ReceiverListModel::clear() { replace({}, {}, {}, {}); }

} // namespace shackcq::desktop
