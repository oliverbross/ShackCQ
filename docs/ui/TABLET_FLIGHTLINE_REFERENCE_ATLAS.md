# Tablet Flightline Reference Atlas

Reference device: Lenovo TB373FU (`HA248BS3`), package `app.shackcq.mobile` 0.1.0, exact APK SHA-256 `db0568854cfc7e68b66d1985b5363bf492e272ae7459ecbab1131682da54e50a`. The 41 new captures are 2944×1840, unlocked, non-blank, and stored privately under `build/evidence/tablet-reference-ce1b99f-20260825T213846Z/`. They are intentionally ignored by Git because visible operator/provider data may be private.

Capture used one explicit ADB serial, `192.168.4.232:46455`. It launched the already-installed app only. It did not install an APK, clear data, reveal credentials, connect radio/rotator services, invoke PTT/TUNE/Digi TX, or move hardware. Panadapter and EQ destinations were hidden by the saved tablet capability settings; Rotator was reviewed through Settings without altering that configuration.

## Flightline characteristics carried to desktop

- Fixed: destination navigation, workspace identity, global safety/status, and high-value actions.
- Scrollable: long forms, provider inventories, message bodies, activity lists, and secondary settings.
- Hierarchy: workspace first; current operating state second; filters/actions third; dense evidence below.
- Density: 8/12/16 px rhythm, compact controls and tables, dark graphite surfaces, amber selection, written non-colour status.
- Desktop adaptation: resizable panes replace fixed tablet columns; native system Navigate menus replace permanent global side navigation; tables gain pointer/keyboard behavior; macOS uses a global menu and Windows a Win32 menu attached to window chrome.

## Screen atlas

| # | Private screenshot | Navigation path | Major regions / fixed vs scrolling | Primary actions and safety | Desktop adaptation / owner |
|---:|---|---|---|---|---|
| 1 | `01-home.png` | Home | Flightline header, operating tiles, modules, DX list; page scrolls | Open shared intelligence; status remains observational | Adaptive tile grid and module list / Parity, Spots |
| 2 | `02-radio.png` | Radio | Profile/control column and radio evidence; bounded lists scroll | Explicit Connect/Disconnect, RX review; TX unavailable | Resizable control/evidence panes / Radio |
| 3 | `03-digi.png` | Digi | Mode registry, decoder/chaser foundations | Review actions only; no automatic TX | Dense mode/activity workspace / Parity |
| 4 | `04-contest.png` | Contest > Setup | Setup, session state, contest definitions | Create/review; automation inactive | Split setup/log/review panes / Parity |
| 5 | `05-band-maps.png` | Band Maps | Band columns and spot evidence | Layout selection, receive review | Resizable vertical/horizontal/grid layouts / Parity, Spots |
| 6 | `06-logbook.png` | Logbook | Filter bar, fixed table header, QSO rows | Fast Entry, Import, Export | Wide sortable data table / LogbookModel, Adif |
| 7 | `07-intelligence-overview.png` | Intelligence > Overview | Tabs, status summary, opportunity lists | Filter and evidence inspection | Tabbed multi-column evidence / Parity |
| 8 | `08-presets.png` | Presets | Preset groups and safe application review | Select/review preset; no blind CAT | Master-detail list / Parity, Radio |
| 9 | `09-dx.png` | DX | Outlook/opportunity evidence and details | Prepare receive review | Table plus inspector / Parity |
| 10 | `10-portable.png` | Portable | Activity map/list and source status | Filter/select only | Split map/list with minimum panes / Parity |
| 11 | `11-operations.png` | Operations | Planner, satellite and QO-100 tabs | Select/review; no Doppler/TX automation | Desktop tabs and inspector / Parity |
| 12 | `12-groups.png` | Groups.io | Groups, topic list, message panel | Draft/review; nothing posts automatically | Three-pane mailbox layout / Parity |
| 13 | `13-settings.png` | Settings > Radio | Category navigation and detail form | Save safe preferences | Searchable settings sidebar / DesktopConfig, Radio |
| 14 | `14-shack-display.png` | View > Shack Display | Large clock/frequency/status, minimal chrome | Visible Global Stop | Full-window glance display / Desktop, Radio |
| 15 | `15-digi-dx-chaser.png` | Digi > DX Chaser | Chaser candidates and evidence | Prepare only; TX gated | Table plus review inspector / Parity |
| 16 | `16-logbook-filters.png` | Logbook > Filters | Filter controls over QSO table | Apply/reset filters | Fixed action/filter bar / LogbookModel |
| 17 | `17-intelligence-geography.png` | Intelligence > Geography | Map/globe, filters, selected evidence | Observation selection | Resizable map and evidence rail / RfObservations |
| 18 | `18-contest-logging.png` | Contest > Logging | Fast-entry area and live session log | Local save/review | Keyboard-first logging split / Parity |
| 19 | `19-contest-review.png` | Contest > Review | Candidate merges and review detail | Explicit confirmation required | Queue plus inspector / Parity |
| 20 | `20-contest-network.png` | Contest > Network | N1MM/network state and diagnostics | Enable/refresh only when configured | Status table and repair actions / Parity |
| 21 | `21-bandmaps-multi-vertical.png` | Band Maps > Multi vertical | Parallel band columns | Layout selection | Persisted split ratios / Parity |
| 22 | `22-bandmaps-multi-horizontal.png` | Band Maps > Multi horizontal | Stacked band rows | Layout selection | Horizontal split layout / Parity |
| 23 | `23-bandmaps-grid.png` | Band Maps > Grid | Dense grid of band panels | Layout selection | Adaptive two/three column grid / Parity |
| 24 | `24-bandmaps-single-expanded.png` | Band Maps > Single | One expanded band and inspector | Receive review | Single-pane focus mode / Parity |
| 25 | `25-dx-map.png` | DX > Map | Map with filtered opportunity evidence | Select observation | Map plus inspector / RfObservations |
| 26 | `26-dx-outlook.png` | DX > Outlook | Ranked opportunity table and explanation | Review only | Sortable table / Parity |
| 27 | `27-dx-rf-evidence.png` | DX > RF Evidence | Source, age, band/mode and path evidence | Filter/select | Shared flat/globe state / RfObservations |
| 28 | `28-portable-map.png` | Portable > Map | Map and portable activity | Select activity | Minimum-width map/list split / Parity |
| 29 | `29-portable-places.png` | Portable > Places | Place/reference list and details | Filter/select | Table plus inspector / Parity |
| 30 | `30-operations-planner.png` | Operations > Planner | Timeline/tasks and selected detail | Prepare receive review | Timeline/list composition / Parity |
| 31 | `31-satellite-operations.png` | Operations > Satellite | Pass list, geometry and selected pass | Selection only; no automatic follow | Table plus pass inspector / Parity |
| 32 | `32-qo100.png` | Operations > QO-100 | Beacon/planner foundations | Review only | Compact reference workspace / Parity |
| 33 | `33-groups-topic-list.png` | Groups.io > Topics | Group/topic list and unread state | Select topic | Master-detail panes / Parity |
| 34 | `34-groups-message.png` | Groups.io > Message | Thread body and draft/review region | Draft only; no automatic post | Reading pane with bounded measure / Parity |
| 35 | `35-settings-alerts.png` | Settings > Alerts | Alert profiles and status semantics | Save preferences | Settings category detail / DesktopConfig |
| 36 | `36-settings-integrations.png` | Settings > Integrations | Providers, Wavelog, Groups.io state | Explicit enable/refresh | Provider lifecycle table / Parity, Wavelog |
| 37 | `37-settings-bandmaps.png` | Settings > Band Maps | Layout/default settings | Save safe layout | Desktop breakpoint preview / DesktopConfig |
| 38 | `38-settings-rotator.png` | Settings > Rotator | Profile and safety state | Remains disarmed; no motion | Capability-gated settings / Rotator |
| 39 | `39-settings-health.png` | Settings > Health | Stores, providers and repair status | Inspect/repair actions | Two/three-column diagnostics / Desktop, Parity |
| 40 | `40-about.png` | About / Licences | Identity, versions, incorporated software, licences | Source/licence navigation | Scrollable acknowledgements / build info |
| 41 | `41-sync-hub.png` | Sync | Local/remote lifecycle and conflict state | Explicit sync/review | Queue/status workspace / Wavelog, LogbookModel |

The atlas proves visual reference only. It is not authenticated-provider, audio, CAT/PTT/TUNE, RF, rotator-movement, or physical Windows evidence.
