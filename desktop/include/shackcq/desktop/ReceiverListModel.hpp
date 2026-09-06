#pragma once

#include <QAbstractListModel>
#include <QVariantList>

namespace shackcq::desktop {

class ReceiverListModel final : public QAbstractListModel {
  Q_OBJECT
  Q_PROPERTY(int count READ rowCount NOTIFY countChanged)
public:
  enum Role {
    IdRole = Qt::UserRole + 1,
    LabelRole,
    BackendIndexRole,
    EnabledRole,
    MutedRole,
    ActiveControlRole,
    ActiveListeningRole,
    TransmitOwnerRole,
    CentreFrequencyRole,
    VfoARole,
    VfoBRole,
    SelectedChannelRole,
    EffectiveReceiveRole,
    ModeRole,
    FilterLowRole,
    FilterHighRole,
    SampleRateRole,
    IqStateRole,
    AudioStateRole,
    SignalDbmRole,
    ForwardPowerRole,
    SwrRole,
    LastObservedRole,
    DroppedFramesRole,
    StaleRole,
    ErrorRole
  };

  explicit ReceiverListModel(QObject *parent = nullptr);
  int rowCount(const QModelIndex &parent = {}) const override;
  QVariant data(const QModelIndex &index, int role) const override;
  QHash<int, QByteArray> roleNames() const override;
  QVariantList snapshots() const { return m_rows; }
  QVariantMap receiver(const QString &id) const;
  void replace(QVariantList rows, const QString &activeId,
               const QString &listeningId, const QString &transmitId);
  void clear();

signals:
  void countChanged();

private:
  QVariantList m_rows;
};

} // namespace shackcq::desktop
