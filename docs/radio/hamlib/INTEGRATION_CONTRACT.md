# Integration contract

This task exports an isolated Hamlib platform under `app.shackcq.mobile.radio.hamlib`. It does not integrate it into `MainActivity`, `AppController`, `RadioBackend`, `NativeCore`, `RadioState`, the central Radio screen, Panadapter, Digi, Configuration Recovery, Health, or Apple code.

An adopting task must:

1. provide the sole Android USB serial owner through `HamlibSerialTransportPort`;
2. store only `HamlibSettingsDocument` data and preserve the default read-only/network-disabled values;
3. collect controller state flows rather than call JNI from UI;
4. treat `HamlibAction.SetPtt` and `HamlibAction.Tune` as transmit-authority operations, distinct from ordinary controls;
5. disconnect on owner lifecycle loss and never restore a transmit state;
6. perform device, USB permission, model-specific CAT, and RF acceptance separately.

Model IDs are Hamlib identifiers. Settings migration must retain the pinned Hamlib version/source digest and require review if an upstream update removes or materially changes a saved model.
