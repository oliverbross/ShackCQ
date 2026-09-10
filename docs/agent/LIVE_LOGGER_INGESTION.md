# Station Agent live logger ingestion

This optional Agent capability receives completed contacts from WSJT-X or N1MM+ over a separately reviewed loopback UDP profile. It does not require or open a radio, does not use Hamlib/CAT, and sends no command back to either logger.

## Setup and safe test

1. Pair the Station Agent normally. In ShackCQ open **Settings → Live Logging**.
2. Create a source for the intended Agent, station, logger instance and UDP port. New profiles are disabled and default to review mode.
3. To prove packet receipt without writing a QSO, choose **Test receipt only**, enable the reviewed profile, then configure the logger to send to `127.0.0.1` and that port.
4. WSJT-X: enable UDP reporting for QSO Logged (type 5) / Logged ADIF (type 12). N1MM+: enable ContactInfo, ContactReplace and ContactDelete external broadcasts. Do not enable port forwarding or radio-control commands.
5. Observe the test disposition in **Logbook → Live Logging inbox**. Test mode never creates a Local QSO or a Wavelog operation.

Review mode is the safe default. Automatic create is a separate explicit mode plus enable action. Source-owned edits and deletes always enter review and require an unambiguous source link and canonical revision. A capture-time authority, station or Wavelog mapping change holds the event instead of rerouting it.

UDP can be lost before the Agent receives it. Once accepted locally, the encrypted journal survives Agent/cloud restart and replays the same event until a durable receipt. ADIF import remains the recovery path for packets that never reached the Agent.

Protocol authority: WSJT-X `Network/NetworkMessage.hpp` at `ccdfaf3c1c109010d15399674ce278167cfde848`; N1MM Logger+ official External UDP Broadcasts documentation reviewed 2026-09-11.
