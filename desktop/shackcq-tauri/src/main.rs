// SPDX-License-Identifier: GPL-3.0-only
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use shackcq_nexus_runtime::{
    CommandEnvelope, CommandResult, DigiMode, ReviewedContact, RuntimeCommand, RuntimePresence,
    RuntimeState, RxProfile, CONTRACT_VERSION, MAX_RECORDING_BYTES,
};
use std::collections::VecDeque;
use std::fs::OpenOptions;
use std::io::{BufRead, BufReader, Write};
#[cfg(unix)]
use std::os::unix::fs::OpenOptionsExt;
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{Manager, State};
mod agent_ingress;
mod loopback;

const AGENT_ID: &str = "shackcq-desktop-local";
const DEVICE_ID: &str = "nexus-native-rx";
const RECORDING_LOCAL_ONLY: bool = true;
const PRODUCTION_ORIGIN: &str = "https://shackcq.com";
const ISOLATED_REVIEW_ORIGIN: &str = "https://localhost:18443";
const REVIEW_FIXTURE_ADIF_KEY: &str = "APP_SHACKCQ_REVIEW_FIXTURE";
const REVIEW_FIXTURE_ADIF_VALUE: &str = "1";
const MAX_FROZEN_REVIEWED_OPERATIONS: usize = 5_000;

#[derive(Clone)]
struct ReviewProfile {
    origin: String,
    instance_id: String,
    root: std::path::PathBuf,
    admin_socket: String,
    ingress_secret: String,
    tls_certificate: std::path::PathBuf,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ReviewContext {
    enabled: bool,
    label: &'static str,
    origin: String,
    instance_id: String,
}

fn load_review_profile(app: &tauri::AppHandle) -> Result<Option<ReviewProfile>, String> {
    if !cfg!(feature = "isolated-review") {
        return Ok(None);
    }
    let origin = option_env!("SHACKCQ_ISOLATED_REVIEW_ORIGIN")
        .ok_or("isolated review origin was not pinned at build time")?;
    if origin != ISOLATED_REVIEW_ORIGIN || origin == PRODUCTION_ORIGIN {
        return Err("isolated review origin is not the approved local TLS fixture".into());
    }
    let tls_certificate = std::env::var("SHACKCQ_ISOLATED_REVIEW_TLS_CERT")
        .map(std::path::PathBuf::from)
        .map_err(|_| "SHACKCQ_ISOLATED_REVIEW_TLS_CERT is required")?;
    if !tls_certificate.is_file() {
        return Err("isolated review TLS certificate is unavailable".into());
    }
    let mut random = [0u8; 16];
    getrandom::fill(&mut random).map_err(|_| "isolated review identity unavailable")?;
    let instance_id = hex::encode(random);
    let mut secret = [0u8; 32];
    getrandom::fill(&mut secret).map_err(|_| "isolated review IPC secret unavailable")?;
    let ingress_secret = hex::encode(secret);
    let admin_socket = format!("shackcq-review-{instance_id}");
    agent_ingress::configure_isolated_review(admin_socket.clone(), &ingress_secret)?;
    let root = app
        .path()
        .app_data_dir()
        .map_err(|_| "isolated review profile root unavailable")?
        .join(&instance_id);
    std::fs::create_dir_all(&root).map_err(|_| "isolated review profile root unavailable")?;
    Ok(Some(ReviewProfile {
        origin: origin.into(),
        instance_id,
        root,
        admin_socket,
        ingress_secret,
        tls_certificate,
    }))
}

fn package_acceptance_exit_ms() -> Option<u64> {
    std::env::var("SHACKCQ_PACKAGE_ACCEPTANCE_EXIT_AFTER_MS")
        .ok()
        .and_then(|value| value.parse::<u64>().ok())
        .filter(|value| (100..=10_000).contains(value))
}

struct RuntimeSupervisor {
    _child: Child,
    input: ChildStdin,
    output: BufReader<ChildStdout>,
    nonce: String,
    generation: u64,
    sequence: AtomicU64,
}
impl RuntimeSupervisor {
    fn spawn(
        app: &tauri::AppHandle,
        pid: &AtomicU32,
        review: Option<&ReviewProfile>,
    ) -> Result<Self, String> {
        let mut nonce = [0u8; 32];
        getrandom::fill(&mut nonce).map_err(|_| "runtime nonce unavailable")?;
        let nonce = hex::encode(nonce);
        let queue_key = if package_acceptance_exit_ms().is_some() || review.is_some() {
            let mut key = [0u8; 32];
            getrandom::fill(&mut key).map_err(|_| "queue key unavailable")?;
            hex::encode(key)
        } else {
            let entry = keyring::Entry::new("ShackCQ Desktop", "nexus-reviewed-contact-queue")
                .map_err(|_| "credential vault unavailable")?;
            match entry.get_password() {
                Ok(value) if value.len() == 64 => value,
                _ => {
                    let mut key = [0u8; 32];
                    getrandom::fill(&mut key).map_err(|_| "queue key unavailable")?;
                    let value = hex::encode(key);
                    entry
                        .set_password(&value)
                        .map_err(|_| "credential vault unavailable")?;
                    value
                }
            }
        };
        let executable = std::env::current_exe()
            .map_err(|_| "desktop executable unavailable")?
            .parent()
            .ok_or("desktop directory unavailable")?
            .join(if cfg!(windows) {
                "shackcq-nexus-runtime.exe"
            } else {
                "shackcq-nexus-runtime"
            });
        let queue_path = match review {
            Some(profile) => profile.root.join("nexus-reviewed-contacts.bin"),
            None => app
                .path()
                .app_data_dir()
                .map_err(|_| "application data directory unavailable")?
                .join("nexus-reviewed-contacts.bin"),
        };
        let mut child = Command::new(executable)
            .env_clear()
            .env("SHACKCQ_RUNTIME_NONCE", &nonce)
            .env("SHACKCQ_QUEUE_KEY_HEX", queue_key)
            .env("SHACKCQ_QUEUE_PATH", queue_path)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|_| "station runtime failed to start")?;
        pid.store(child.id(), Ordering::Release);
        let input = child
            .stdin
            .take()
            .ok_or("station runtime input unavailable")?;
        let output = BufReader::new(
            child
                .stdout
                .take()
                .ok_or("station runtime output unavailable")?,
        );
        Ok(Self {
            _child: child,
            input,
            output,
            nonce,
            generation: 1,
            sequence: AtomicU64::new(1),
        })
    }
    fn request(&mut self, command: RuntimeCommand) -> Result<CommandResult, String> {
        let command_id = format!("desktop-{}", self.sequence.fetch_add(1, Ordering::Relaxed));
        self.request_named(command, command_id, self.generation)
    }
    fn request_named(
        &mut self,
        command: RuntimeCommand,
        command_id: String,
        expected_generation: u64,
    ) -> Result<CommandResult, String> {
        let is_stop = matches!(command, RuntimeCommand::Stop { .. });
        if !generation_matches(self.generation, expected_generation, is_stop) {
            return Ok(CommandResult {
                version: CONTRACT_VERSION,
                command_id,
                generation: self.generation,
                ok: false,
                code: "STALE_GENERATION".into(),
                payload: Value::Null,
            });
        }
        let envelope = make_envelope(&self.nonce, command, command_id, expected_generation);
        serde_json::to_writer(&mut self.input, &envelope)
            .map_err(|_| "runtime IPC encode failed")?;
        self.input
            .write_all(b"\n")
            .and_then(|_| self.input.flush())
            .map_err(|_| "runtime IPC write failed")?;
        let mut line = String::new();
        self.output
            .read_line(&mut line)
            .map_err(|_| "runtime IPC read failed")?;
        if line.is_empty() || line.len() > shackcq_nexus_runtime::MAX_COMMAND_BYTES {
            return Err("runtime IPC response invalid".into());
        }
        let response: CommandResult =
            serde_json::from_str(&line).map_err(|_| "runtime IPC response invalid")?;
        self.generation = response.generation;
        Ok(response)
    }
}

impl Drop for RuntimeSupervisor {
    fn drop(&mut self) {
        let _ = self._child.kill();
        let _ = self._child.wait();
    }
}
fn generation_matches(actual: u64, expected: u64, is_stop: bool) -> bool {
    is_stop || actual == expected
}
fn make_envelope(
    nonce: &str,
    command: RuntimeCommand,
    command_id: String,
    generation: u64,
) -> CommandEnvelope {
    CommandEnvelope {
        version: CONTRACT_VERSION,
        command_id,
        generation,
        launch_nonce: nonce.into(),
        command,
    }
}
struct EmergencyStop {
    pid: AtomicU32,
}
impl EmergencyStop {
    fn trip(&self) -> bool {
        let pid = self.pid.swap(0, Ordering::AcqRel);
        if pid == 0 {
            return true;
        }
        kill_process(pid)
    }
}
#[cfg(unix)]
fn kill_process(pid: u32) -> bool {
    unsafe { libc::kill(pid as libc::pid_t, libc::SIGKILL) == 0 }
}
#[cfg(windows)]
fn kill_process(pid: u32) -> bool {
    unsafe {
        use windows_sys::Win32::Foundation::CloseHandle;
        use windows_sys::Win32::System::Threading::{
            OpenProcess, TerminateProcess, PROCESS_TERMINATE,
        };
        let h = OpenProcess(PROCESS_TERMINATE, 0, pid);
        if h.is_null() {
            return false;
        }
        let ok = TerminateProcess(h, 1) != 0;
        CloseHandle(h);
        ok
    }
}
struct Backend {
    runtime: Arc<Mutex<RuntimeSupervisor>>,
    emergency: Arc<EmergencyStop>,
    browser_local_available: Arc<AtomicBool>,
    agent_state: Mutex<String>,
    review: Option<ReviewContext>,
    reviewed_operations: Mutex<FrozenReviewedOperations>,
}

#[derive(Clone)]
struct FrozenReviewedOperation {
    radio_device_id: String,
    contact: ReviewedContact,
}

#[derive(Default)]
struct FrozenReviewedOperations {
    rows: VecDeque<FrozenReviewedOperation>,
}

impl FrozenReviewedOperations {
    fn freeze(
        &mut self,
        radio_device_id: &str,
        candidate: ReviewedContact,
    ) -> Result<ReviewedContact, &'static str> {
        if let Some(existing) = self
            .rows
            .iter()
            .find(|row| row.contact.operation_identity == candidate.operation_identity)
        {
            let mut comparable = candidate;
            comparable.captured_utc = existing.contact.captured_utc.clone();
            if existing.radio_device_id != radio_device_id || existing.contact != comparable {
                return Err("NATIVE_CONTACT_OPERATION_CHANGED");
            }
            return Ok(existing.contact.clone());
        }
        if self.rows.len() == MAX_FROZEN_REVIEWED_OPERATIONS {
            return Err("NATIVE_CONTACT_OPERATION_STORE_FULL");
        }
        self.rows.push_back(FrozenReviewedOperation {
            radio_device_id: radio_device_id.to_owned(),
            contact: candidate.clone(),
        });
        Ok(candidate)
    }
}
struct AppState {
    backend: Arc<Backend>,
    loopback: Arc<loopback::Controller>,
    agent: Mutex<AgentSupervisor>,
}

struct AgentSupervisor {
    child: Option<Child>,
    owner_token: String,
}
impl AgentSupervisor {
    fn ensure(
        _app: &tauri::AppHandle,
        review: Option<&ReviewProfile>,
    ) -> Result<(Self, String), String> {
        if review.is_some() && agent_ingress::probe() != agent_ingress::AgentProbe::Missing {
            return Err("ISOLATED_REVIEW_AGENT_IDENTITY_IN_USE".into());
        }
        if review.is_none() {
            match agent_ingress::probe() {
                agent_ingress::AgentProbe::NativeIngress => {
                    return Ok((
                        Self {
                            child: None,
                            owner_token: String::new(),
                        },
                        "EXTERNAL_NATIVE_INGRESS".into(),
                    ))
                }
                agent_ingress::AgentProbe::Legacy => {
                    return Ok((
                        Self {
                            child: None,
                            owner_token: String::new(),
                        },
                        "LEGACY_AGENT_HANDOVER_REQUIRED".into(),
                    ))
                }
                agent_ingress::AgentProbe::Missing => {}
            }
        }
        let current = std::env::current_exe().map_err(|_| "desktop executable unavailable")?;
        let executable = current
            .parent()
            .ok_or("desktop directory unavailable")?
            .join(if cfg!(windows) {
                "shackcq-stationd.exe"
            } else {
                "shackcq-stationd"
            });
        let mut token = [0u8; 32];
        getrandom::fill(&mut token).map_err(|_| "AGENT_NATIVE_INGRESS_UNAVAILABLE")?;
        let owner_token = hex::encode(token);
        let mut command = Command::new(executable);
        command
            .arg("--native-ingress-only")
            .arg("--native-owner-token")
            .arg(&owner_token)
            .arg("--admin-socket")
            .arg(agent_ingress::admin_socket_name());
        if let Some(profile) = review {
            command
                .arg("--ephemeral-root")
                .arg(&profile.root)
                .arg("--ephemeral-credentials")
                .arg("--cloud-origin")
                .arg(&profile.origin)
                .arg("--review-tls-cert")
                .arg(&profile.tls_certificate)
                .env("SHACKCQ_NATIVE_INGRESS_SECRET_HEX", &profile.ingress_secret);
        }
        if review.is_none() {
            if let Ok(root) = std::env::var("SHACKCQ_AGENT_EPHEMERAL_ROOT") {
                if !root.is_empty() && root.len() <= 1024 {
                    command.arg("--ephemeral-root").arg(root);
                    if std::env::var("SHACKCQ_AGENT_EPHEMERAL_CREDENTIALS").as_deref() == Ok("1") {
                        command.arg("--ephemeral-credentials");
                    }
                }
            }
        }
        let mut child = command
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|_| "AGENT_NATIVE_INGRESS_UNAVAILABLE")?;
        for _ in 0..100 {
            if agent_ingress::probe() == agent_ingress::AgentProbe::NativeIngress {
                return Ok((
                    Self {
                        child: Some(child),
                        owner_token,
                    },
                    "OWNED_NATIVE_INGRESS".into(),
                ));
            }
            if child.try_wait().ok().flatten().is_some() {
                return Err("AGENT_NATIVE_INGRESS_UNAVAILABLE".into());
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        let _ = child.kill();
        let _ = child.wait();
        Err("AGENT_NATIVE_INGRESS_UNAVAILABLE".into())
    }

    fn owns_child(&self) -> bool {
        self.child.is_some() && self.owner_token.len() == 64
    }

    fn setup_request(&self, action: &str, fields: Value) -> Value {
        if !self.owns_child() {
            return json!({"ok":false,"code":"AGENT_SETUP_EXTERNAL_REQUIRED"});
        }
        agent_ingress::setup_request(action, &self.owner_token, fields)
    }

    fn shutdown(&mut self) {
        let Some(mut child) = self.child.take() else {
            return;
        };
        agent_ingress::request_owned_stop(&self.owner_token);
        for _ in 0..40 {
            if child.try_wait().ok().flatten().is_some() {
                return;
            }
            std::thread::sleep(Duration::from_millis(25));
        }
        let _ = child.kill();
        let _ = child.wait();
    }
}
impl Drop for AgentSupervisor {
    fn drop(&mut self) {
        self.shutdown();
    }
}
fn call_state(state: &Backend, command: RuntimeCommand) -> Result<CommandResult, String> {
    state
        .runtime
        .lock()
        .map_err(|_| "runtime supervisor unavailable".to_string())?
        .request(command)
}
fn call_named_state(
    state: &Backend,
    command: RuntimeCommand,
    id: String,
    generation: u64,
) -> Result<CommandResult, String> {
    state
        .runtime
        .lock()
        .map_err(|_| "runtime supervisor unavailable".to_string())?
        .request_named(command, id, generation)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Target {
    agent_id: String,
    device_id: String,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AudioSelection {
    command_id: String,
    agent_id: String,
    device_id: String,
    expected_generation: u64,
    input_device_id: String,
    input_channel: u16,
    output_device_id: Option<String>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct StopScope {
    command_id: String,
    agent_id: Option<String>,
    device_id: Option<String>,
    expected_generation: Option<u64>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct CommandFrame {
    contract: Contract,
    command_id: String,
    session_id: Option<String>,
    valid_until_utc: String,
    agent_id: String,
    device_id: String,
    expected_generation: u64,
    control_instance_id: String,
    action: String,
    parameters: Value,
}
#[derive(Deserialize, Serialize)]
struct Contract {
    major: u8,
    minor: u8,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct CompletedContactIntent {
    station_profile_id: String,
    radio_device_id: String,
    operation_identity: String,
    completed: bool,
    user_authorized: bool,
    qso: Value,
}

fn valid_review_callsign(value: &str) -> bool {
    (3..=32).contains(&value.len())
        && value.split('/').all(|part| {
            !part.is_empty()
                && part
                    .bytes()
                    .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit())
        })
}

fn normalize_logger_contact(qso: &Value, fixture: bool) -> Result<Value, &'static str> {
    let row = qso.as_object().ok_or("NATIVE_CONTACT_INVALID")?;
    let mut keys = row.keys().map(String::as_str).collect::<Vec<_>>();
    keys.sort_unstable();
    if keys
        != [
            "callsign",
            "comment",
            "contactStartUtc",
            "frequencyHz",
            "mode",
            "submode",
        ]
    {
        return Err("NATIVE_CONTACT_INVALID");
    }
    let callsign = row["callsign"]
        .as_str()
        .filter(|value| valid_review_callsign(value))
        .ok_or("NATIVE_CONTACT_INVALID")?;
    let mode = row["mode"]
        .as_str()
        .filter(|value| {
            matches!(
                *value,
                "FT8" | "FT4" | "FT2" | "FST4" | "Q65" | "MSK144" | "JT65"
            )
        })
        .ok_or("NATIVE_CONTACT_INVALID")?;
    let contact_start_utc = row["contactStartUtc"]
        .as_str()
        .filter(|value| {
            chrono::DateTime::parse_from_rfc3339(value)
                .map(|parsed| parsed.offset().local_minus_utc() == 0)
                .unwrap_or(false)
        })
        .ok_or("NATIVE_CONTACT_INVALID")?;
    let frequency_hz = row["frequencyHz"]
        .as_u64()
        .filter(|value| (1..=10_500_000_000).contains(value))
        .ok_or("NATIVE_CONTACT_INVALID")?;
    let comment = row["comment"]
        .as_str()
        .filter(|value| value.len() <= 512)
        .ok_or("NATIVE_CONTACT_INVALID")?;
    let submode = match &row["submode"] {
        Value::Null => None,
        Value::String(value) if value.len() <= 16 => Some(value.as_str()),
        _ => return Err("NATIVE_CONTACT_INVALID"),
    };
    let mut contact = serde_json::Map::new();
    contact.insert("callsign".into(), Value::String(callsign.into()));
    contact.insert("mode".into(), Value::String(mode.into()));
    if let Some(value) = submode {
        contact.insert("submode".into(), Value::String(value.into()));
    }
    contact.insert("frequencyHz".into(), Value::Number(frequency_hz.into()));
    contact.insert(
        "contactStartUtc".into(),
        Value::String(contact_start_utc.into()),
    );
    let mut adif = serde_json::Map::new();
    if !comment.is_empty() {
        adif.insert("COMMENT".into(), Value::String(comment.into()));
    }
    if fixture {
        adif.insert(
            REVIEW_FIXTURE_ADIF_KEY.into(),
            Value::String(REVIEW_FIXTURE_ADIF_VALUE.into()),
        );
    }
    if !adif.is_empty() {
        contact.insert("adif".into(), Value::Object(adif));
    }
    Ok(Value::Object(contact))
}

fn reviewed_contact_from_intent(
    intent: &CompletedContactIntent,
    binding: &Value,
    review: Option<&ReviewContext>,
    captured_utc: String,
) -> Result<ReviewedContact, &'static str> {
    let fixture = review
        .map(|context| {
            context.enabled
                && context.origin == ISOLATED_REVIEW_ORIGIN
                && intent.operation_identity == format!("review-contact-{}", context.instance_id)
        })
        .unwrap_or(false);
    if intent.operation_identity.starts_with("review-contact-") && !fixture {
        return Err("REVIEW_FIXTURE_CONTEXT_REJECTED");
    }
    let destination_authority = binding["destinationAuthority"]
        .as_str()
        .ok_or("LOGGER_DESTINATION_CHANGED")?;
    if fixture && destination_authority != "WEB_LOCAL" {
        return Err("REVIEW_FIXTURE_DESTINATION_REJECTED");
    }
    Ok(ReviewedContact {
        event_id: intent.operation_identity.clone(),
        operation_identity: intent.operation_identity.clone(),
        profile_id: binding["profileId"]
            .as_str()
            .ok_or("LOGGER_NATIVE_PROFILE_UNAVAILABLE")?
            .to_owned(),
        source_revision: binding["sourceRevision"]
            .as_u64()
            .filter(|value| *value > 0)
            .ok_or("LOGGER_NATIVE_PROFILE_UNAVAILABLE")? as u32,
        account_id: binding["accountId"]
            .as_str()
            .ok_or("LOGGER_ACCOUNT_SCOPE_REQUIRED")?
            .to_owned(),
        station_profile_id: intent.station_profile_id.clone(),
        destination_authority: destination_authority.to_owned(),
        authority_revision: binding["authorityRevision"]
            .as_u64()
            .filter(|value| *value > 0)
            .ok_or("LOGGER_DESTINATION_CHANGED")? as u32,
        mapping_revision: binding["mappingRevision"]
            .as_u64()
            .map(|value| value as u32)
            .filter(|value| *value > 0),
        captured_utc,
        provenance: "NEXUS_NATIVE".into(),
        fixture,
        contact: normalize_logger_contact(&intent.qso, fixture)?,
    })
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CommandReply {
    ok: bool,
    code: String,
}

fn valid_target(agent: &str, device: &str) -> bool {
    agent == AGENT_ID && device == DEVICE_ID
}
fn valid_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._:-".contains(&b))
}
fn bounded_json<T: Serialize>(value: &T, maximum: usize) -> bool {
    serde_json::to_vec(value)
        .map(|v| v.len() <= maximum)
        .unwrap_or(false)
}
fn now() -> String {
    chrono::Utc::now().to_rfc3339()
}
fn parse_presence(result: CommandResult) -> Result<RuntimePresence, String> {
    if !result.ok {
        return Err(result.code);
    }
    serde_json::from_value(result.payload).map_err(|_| "runtime presence invalid".into())
}
fn runtime_presence(p: &RuntimePresence) -> Value {
    let s = &p.snapshot;
    let profile = s.rx_profile.as_ref();
    let mode = profile
        .map(|x| match x.mode {
            DigiMode::Ft8 => "FT8",
            DigiMode::Ft4 => "FT4",
            DigiMode::Ft2 => "FT2",
            DigiMode::Fst4 => "FST4",
            DigiMode::Fst4w => "FST4W",
            DigiMode::Q65 => "Q65",
            DigiMode::Msk144 => "MSK144",
            DigiMode::Jt65 => "JT65",
            DigiMode::Wspr => "WSPR",
        })
        .unwrap_or("FT8");
    let submode = profile.and_then(|value| value.submode.clone());
    let state = match s.state {
        RuntimeState::Receiving => "RX",
        RuntimeState::Stopped => "RX_VERIFIED",
        RuntimeState::RxUnconfirmed => "RX_UNCONFIRMED",
        _ => "SAFE",
    };
    let session = if matches!(s.state, RuntimeState::Receiving) {
        Some(format!("local-session-{}", s.generation))
    } else {
        None
    };
    let session_id = session
        .clone()
        .unwrap_or_else(|| format!("idle-{}", s.generation));
    let devices=p.input_devices.iter().map(|d|json!({"id":d.id,"label":d.label,"kind":"INPUT","channels":[],"sampleRates":[],"state":"AVAILABLE"}))
  .chain(p.output_devices.iter().map(|d|json!({"id":d.id,"label":d.label,"kind":"OUTPUT","channels":[],"sampleRates":[],"state":"AVAILABLE"}))).collect::<Vec<_>>();
    let decodes=p.decodes.iter().map(|d|json!({"id":format!("decode-{}",d.sequence),"slotStartMillis":d.slot_start_millis,"source":if d.fixture{"REFERENCE_RECORDING"}else{"LIVE_CAPTURE"},"exactSlotTiming":d.exact_slot_timing,"snr":d.snr_db,"dt":d.dt_seconds,"audioHz":d.audio_hz,"text":d.message})).collect::<Vec<_>>();
    let waterfall=p.waterfall.iter().map(|w|json!({"rowId":format!("waterfall-{}",w.sequence),"sessionId":session_id,"observedUtc":chrono::DateTime::from_timestamp_millis(w.observed_unix_millis as i64).unwrap_or_default().to_rfc3339(),"lowHz":w.low_hz,"highHz":w.high_hz,"scale":"DB_QUANTIZED_0_255","bins":w.bins})).collect::<Vec<_>>();
    let audio_state = match s.state {
        RuntimeState::Receiving => "CAPTURING",
        RuntimeState::Configured => "READY",
        _ => "UNCONFIGURED",
    };
    json!({"state":"online","generation":s.generation,"serverDigiTxEnabled":false,"path":"LOCAL",
 "snapshot":{"type":"digi.snapshot","protocol":{"major":1,"minor":2},"agentId":AGENT_ID,"deviceId":DEVICE_ID,"generation":s.generation,"sequence":s.event_sequence.max(1),"observedUtc":now(),"sessionId":session,"contextGeneration":s.generation,"state":state,"mode":mode,"submode":submode,"dialFrequencyHz":null,"rxAudioHz":1500,"txAudioHz":1500,
 "audio":{"state":audio_state,"inputDeviceId":profile.map(|x|x.device_id.clone()),"outputDeviceId":profile.and_then(|x|x.output_device_id.clone()),"sampleRate":profile.map(|x|x.input_rate_hz).unwrap_or(12000),"channels":1,"rms":0,"peak":0,"clipped":false,"detail":"Receive-only Nexus capture; levels update is pending"},
 "clock":{"state":"UNKNOWN","utcUncertaintyMs":null,"sampleUncertaintyMs":null,"nextSlotUtc":null},"lease":{"state":"NONE","controlInstanceId":null,"expiresUtc":null},
 "tx":{"implemented":false,"serverPermitted":false,"locallyPermitted":false,"hardwareAccepted":false,"armed":false,"transmitting":false,"rxVerified":matches!(s.state,RuntimeState::Stopped),"detail":s.capabilities.tx_lock_reason},
 "capabilities":{"modes":["FT8","FT4","FT2","FST4","FST4W","Q65","MSK144","JT65","WSPR"],"autoSequenceModes":[],"spectrumBins":1024,"waterfallRowsPerSecond":0,"recordingLocalOnly":RECORDING_LOCAL_ONLY,"companionAuthoritative":false},"decodes":decodes,"waterfall":waterfall,"sstv":{}},
 "runtime":{"contract":{"major":1,"minor":0},"engine":{"name":s.identity.engine,"upstreamRevision":s.identity.upstream_commit,"patchSet":"shackcq-rx-only-v1","componentVersion":s.identity.adapter_version,"compiledModes":["FT8","FT4","FT2","FST4","FST4W","Q65","MSK144","JT65","WSPR"],"execution":"VERIFIED_NATIVE"},"audioDevices":devices,
 "audio":{"state":audio_state,"inputDeviceId":profile.map(|x|x.device_id.clone()),"inputLabel":null,"inputChannel":profile.map(|x|x.channel),"outputDeviceId":profile.and_then(|x|x.output_device_id.clone()),"outputLabel":null,"openedSampleRate":if matches!(s.state,RuntimeState::Receiving){profile.map(|x|x.input_rate_hz)}else{None},"channels":if matches!(s.state,RuntimeState::Receiving){Some(1)}else{None},"rmsDbfs":null,"peakDbfs":null,"clipped":false,"detail":"No device is opened until Start RX"},
 "clock":{"state":"UNKNOWN","utcUncertaintyMs":null,"sampleUncertaintyMs":null,"nextSlotUtc":null,"evidence":"No bounded clock measurement yet"},"safety":{"state":state,"serverPermitted":false,"locallyPermitted":false,"hardwareAccepted":false,"armed":false,"transmitting":false,"reason":s.capabilities.tx_lock_reason},"session":{"sessionId":session,"generation":s.generation,"sequence":s.event_sequence,"state":if matches!(s.state,RuntimeState::Receiving){"RECEIVING"}else{"IDLE"},"mode":mode,"startedUtc":null,"endedUtc":null,"detail":"Exact slot timing unavailable"},"queue":{"pendingContacts":s.pending_contacts,"maximumContacts":5000,"pendingBytes":0,"maximumBytes":33554432,"oldestUtc":null,"saturated":s.pending_contacts>=5000}}})
}

fn targets_from_agent(status: &Value, binding: &Value) -> Value {
    let cloud = status.get("cloudAgent").unwrap_or(&Value::Null);
    let paired = cloud
        .get("paired")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let station_profile_id = cloud
        .get("stationProfileId")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .unwrap_or("local-unbound");
    let station_label = cloud
        .get("stationLabel")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .unwrap_or("No ShackCQ station paired");
    let callsign = station_label
        .rsplit('·')
        .next()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or(station_label);
    let binding_ready = binding.get("ok").and_then(Value::as_bool).unwrap_or(false)
        && binding.get("stationProfileId").and_then(Value::as_str) == Some(station_profile_id);
    let stations = if paired {
        vec![
            json!({"id":station_profile_id,"name":station_label,"callsign":callsign,"gridLocator":null}),
        ]
    } else {
        Vec::new()
    };
    json!({"agents":[{"id":AGENT_ID,"name":if paired {station_label} else {"ShackCQ Desktop local runtime"},"stationProfileId":station_profile_id,"protocolMinor":2,"presence":"online","canonicalBindingReady":binding_ready}],"radios":[{"id":DEVICE_ID,"agentId":AGENT_ID,"deviceId":DEVICE_ID,"name":"Nexus native receive runtime","manufacturer":"ShackCQ","model":"Nexus v1.10.3 RX-only"}],"stations":stations})
}

#[tauri::command]
fn digi_list_targets(_state: State<'_, AppState>) -> Value {
    list_targets_backend()
}

fn list_targets_backend() -> Value {
    let status = agent_ingress::setup_status();
    let binding = agent_ingress::binding("desktop-targets");
    targets_from_agent(&status, &binding)
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentPairingInput {
    code: String,
    name: String,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RecordingInput {
    name: String,
    mode: DigiMode,
    bytes_base64: String,
}

fn valid_recording_input(input: &RecordingInput) -> bool {
    !input.name.is_empty()
        && input.name.len() <= 128
        && input.name.chars().all(|value| !value.is_control())
        && input.bytes_base64.len() <= ((MAX_RECORDING_BYTES + 2) / 3) * 4
}

fn valid_pairing_input(input: &AgentPairingInput) -> bool {
    let normalized = input
        .code
        .chars()
        .filter(|ch| !ch.is_ascii_whitespace() && *ch != '-')
        .collect::<String>();
    normalized.len() == 12
        && normalized.chars().all(|ch| ch.is_ascii_alphanumeric())
        && !input.name.trim().is_empty()
        && input.name.len() <= 80
        && bounded_json(input, 1024)
}

#[tauri::command]
fn digi_read_agent_setup(state: State<'_, AppState>) -> Value {
    let mut status = agent_ingress::setup_status();
    status["desktopOwnership"] = json!(state
        .agent
        .lock()
        .map(|agent| if agent.owns_child() {
            "OWNED"
        } else {
            "EXTERNAL"
        })
        .unwrap_or("UNAVAILABLE"));
    status
}

#[tauri::command]
fn digi_read_review_context(state: State<'_, AppState>) -> Value {
    state
        .backend
        .review
        .as_ref()
        .map(|context| serde_json::to_value(context).unwrap_or(Value::Null))
        .unwrap_or_else(|| {
            json!({
                "enabled": false,
                "label": "NORMAL DISTRIBUTION",
                "origin": PRODUCTION_ORIGIN,
                "instanceId": Value::Null
            })
        })
}

#[tauri::command]
fn digi_prepare_review_contact(state: State<'_, AppState>) -> Value {
    let Some(review) = state.backend.review.as_ref() else {
        return json!({"enabled":false,"code":"REVIEW_FIXTURE_UNAVAILABLE"});
    };
    if review.origin != ISOLATED_REVIEW_ORIGIN {
        return json!({"enabled":false,"code":"REVIEW_ORIGIN_REJECTED"});
    }
    let operation_identity = format!("review-contact-{}", review.instance_id);
    let binding = agent_ingress::binding(&operation_identity);
    let station_profile_id = binding
        .get("stationProfileId")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if !binding.get("ok").and_then(Value::as_bool).unwrap_or(false) || !valid_id(station_profile_id)
    {
        return json!({"enabled":true,"code":"REVIEW_ACCOUNT_BINDING_REQUIRED"});
    }
    json!({
        "enabled": true,
        "code": "SYNTHETIC_REVIEW_CONTACT_READY",
        "intent": {
            "stationProfileId": station_profile_id,
            "radioDeviceId": DEVICE_ID,
            "operationIdentity": operation_identity,
            "completed": true,
            "userAuthorized": true,
            "qso": {
                "callsign": "K1ABC",
                "mode": "FT8",
                "submode": Value::Null,
                "frequencyHz": 14_074_000,
                "contactStartUtc": "2026-09-13T06:00:00.000Z",
                "comment": "SYNTHETIC REVIEW CONTACT — DISPOSABLE ENVIRONMENT"
            }
        }
    })
}

#[tauri::command]
fn digi_pair_agent(state: State<'_, AppState>, input: AgentPairingInput) -> Value {
    if !valid_pairing_input(&input) {
        return json!({"ok":false,"code":"AGENT_SETUP_INVALID"});
    }
    state
        .agent
        .lock()
        .map(|agent| {
            agent.setup_request(
                "native-setup.pair",
                json!({"code":input.code,"name":input.name}),
            )
        })
        .unwrap_or_else(|_| json!({"ok":false,"code":"AGENT_SETUP_UNAVAILABLE"}))
}

#[tauri::command]
fn digi_unpair_agent(state: State<'_, AppState>) -> Value {
    state
        .agent
        .lock()
        .map(|agent| agent.setup_request("native-setup.unpair", json!({})))
        .unwrap_or_else(|_| json!({"ok":false,"code":"AGENT_SETUP_UNAVAILABLE"}))
}

struct PrivateRecording {
    path: std::path::PathBuf,
    created: bool,
}

impl Drop for PrivateRecording {
    fn drop(&mut self) {
        if self.created {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

fn write_private_recording(
    path: &std::path::Path,
    bytes: &[u8],
) -> std::io::Result<PrivateRecording> {
    let mut recording = PrivateRecording {
        path: path.to_path_buf(),
        created: false,
    };
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    options.mode(0o600);
    let mut file = options.open(&recording.path)?;
    recording.created = true;
    file.write_all(bytes)?;
    Ok(recording)
}

#[tauri::command]
fn digi_decode_recording(state: State<'_, AppState>, input: RecordingInput) -> Value {
    if !valid_recording_input(&input) {
        return json!({"ok":false,"code":"REFERENCE_RECORDING_INVALID","decodeCount":0});
    }
    let Ok(bytes) = B64.decode(input.bytes_base64.as_bytes()) else {
        return json!({"ok":false,"code":"REFERENCE_RECORDING_INVALID","decodeCount":0});
    };
    if bytes.len() < 44 || bytes.len() > MAX_RECORDING_BYTES {
        return json!({"ok":false,"code":"REFERENCE_RECORDING_INVALID","decodeCount":0});
    }
    let mut random = [0u8; 16];
    if getrandom::fill(&mut random).is_err() {
        return json!({"ok":false,"code":"REFERENCE_RECORDING_TEMP_UNAVAILABLE","decodeCount":0});
    }
    let path = std::env::temp_dir().join(format!("shackcq-reference-{}.wav", hex::encode(random)));
    let Ok(recording) = write_private_recording(&path, &bytes) else {
        return json!({"ok":false,"code":"REFERENCE_RECORDING_TEMP_UNAVAILABLE","decodeCount":0});
    };
    let digest = hex::encode(Sha256::digest(&bytes));
    let outcome = state
        .backend
        .runtime
        .lock()
        .map_err(|_| ())
        .and_then(|mut runtime| {
            runtime
                .request(RuntimeCommand::DecodeRecordingFile {
                    path: recording.path.to_string_lossy().into_owned(),
                    sha256: digest,
                    mode: input.mode,
                })
                .map_err(|_| ())
        });
    match outcome {
        Ok(result) if result.ok => {
            json!({"ok":true,"code":result.code,"decodeCount":result.payload["decodeCount"].as_u64().unwrap_or(0)})
        }
        Ok(result) => json!({"ok":false,"code":result.code,"decodeCount":0}),
        Err(()) => {
            json!({"ok":false,"code":"REFERENCE_RECORDING_RUNTIME_UNAVAILABLE","decodeCount":0})
        }
    }
}
fn presence_backend(state: &Backend, target: Target) -> Result<Value, String> {
    if !valid_target(&target.agent_id, &target.device_id) {
        return Ok(
            json!({"state":"offline","generation":null,"snapshot":null,"serverDigiTxEnabled":false,"path":"DISCONNECTED"}),
        );
    }
    let mut value = runtime_presence(&parse_presence(call_state(
        state,
        RuntimeCommand::Presence,
    )?)?);
    value["runtime"]["browserLocal"] = json!({"state":if state.browser_local_available.load(Ordering::Acquire){"AVAILABLE_APPROVAL_REQUIRED"}else{"UNAVAILABLE"},"origin":"https://shackcq.com","endpoint":"http://127.0.0.1:17654","ipv6":false});
    Ok(value)
}
#[tauri::command]
fn digi_read_presence(state: State<'_, AppState>, target: Target) -> Result<Value, String> {
    presence_backend(state.backend.as_ref(), target)
}
fn configure_backend(state: &Backend, selection: AudioSelection) -> Result<Value, String> {
    if !valid_target(&selection.agent_id, &selection.device_id)
        || !valid_id(&selection.command_id)
        || !valid_id(&selection.input_device_id)
        || selection
            .output_device_id
            .as_deref()
            .is_some_and(|v| !valid_id(v))
        || selection.input_channel > 63
        || !bounded_json(&selection, 16 * 1024)
    {
        return Ok(json!({"ok":false,"code":"INVALID_TARGET"}));
    }
    let current = parse_presence(call_state(state, RuntimeCommand::Presence)?)?;
    if selection.expected_generation != current.snapshot.generation {
        return Ok(json!({"ok":false,"code":"STALE_GENERATION"}));
    }
    let mode = current
        .snapshot
        .rx_profile
        .as_ref()
        .map(|p| p.mode)
        .unwrap_or(DigiMode::Ft8);
    let result = call_named_state(
        state,
        RuntimeCommand::ConfigureRx(RxProfile {
            device_id: selection.input_device_id,
            output_device_id: selection.output_device_id,
            channel: selection.input_channel,
            // Decoder-domain rate only. The opened hardware rate is learned at Start RX.
            input_rate_hz: 12000,
            mode,
            submode: current
                .snapshot
                .rx_profile
                .and_then(|profile| profile.submode),
        }),
        selection.command_id.clone(),
        selection.expected_generation,
    )?;
    Ok(
        json!({"ok":result.ok,"code":result.code,"audioProfileId":if result.ok{Some(format!("audio-{}",result.generation))}else{None}}),
    )
}
#[tauri::command]
fn digi_configure_audio(
    state: State<'_, AppState>,
    selection: AudioSelection,
) -> Result<Value, String> {
    configure_backend(state.backend.as_ref(), selection)
}
fn submit_backend(state: &Backend, frame: CommandFrame) -> Result<CommandReply, String> {
    if frame.contract.major != 1
        || frame.contract.minor > 0
        || !valid_target(&frame.agent_id, &frame.device_id)
        || !valid_id(&frame.command_id)
        || !valid_id(&frame.control_instance_id)
        || !frame.parameters.is_object()
        || !bounded_json(&frame, 48 * 1024)
    {
        return Ok(CommandReply {
            ok: false,
            code: "INVALID_OR_EXPIRED_COMMAND".into(),
        });
    }
    let expires = match chrono::DateTime::parse_from_rfc3339(&frame.valid_until_utc) {
        Ok(v) => v.with_timezone(&chrono::Utc),
        Err(_) => {
            return Ok(CommandReply {
                ok: false,
                code: "INVALID_OR_EXPIRED_COMMAND".into(),
            })
        }
    };
    let remaining = expires - chrono::Utc::now();
    if remaining.num_milliseconds() < 0 || remaining.num_milliseconds() > 10_000 {
        return Ok(CommandReply {
            ok: false,
            code: "INVALID_OR_EXPIRED_COMMAND".into(),
        });
    }
    let stop = matches!(frame.action.as_str(), "digi.rx.stop" | "digi.stop");
    if !stop {
        let expected_session = format!("local-session-{}", frame.expected_generation);
        if frame
            .session_id
            .as_deref()
            .is_some_and(|v| v != expected_session)
        {
            return Ok(CommandReply {
                ok: false,
                code: "STALE_SESSION".into(),
            });
        }
    }
    let command = match frame.action.as_str() {
        "digi.rx.start" => RuntimeCommand::StartReceiving,
        "digi.rx.stop" | "digi.stop" => RuntimeCommand::Stop {
            reason: "shared UI stop".into(),
            radio_rx_readback: None,
        },
        "digi.configure" => {
            let mode = match frame.parameters.get("mode").and_then(Value::as_str) {
                Some("FT8") => DigiMode::Ft8,
                Some("FT4") => DigiMode::Ft4,
                Some("FT2") => DigiMode::Ft2,
                Some("FST4") => DigiMode::Fst4,
                Some("FST4W") => DigiMode::Fst4w,
                Some("Q65") => DigiMode::Q65,
                Some("MSK144") => DigiMode::Msk144,
                Some("JT65") => DigiMode::Jt65,
                Some("WSPR") => DigiMode::Wspr,
                _ => {
                    return Ok(CommandReply {
                        ok: false,
                        code: "MODE_NOT_COMPILED".into(),
                    })
                }
            };
            let current = parse_presence(call_state(state, RuntimeCommand::Presence)?)?;
            let Some(mut profile) = current.snapshot.rx_profile else {
                return Ok(CommandReply {
                    ok: false,
                    code: "AUDIO_NOT_CONFIGURED".into(),
                });
            };
            profile.mode = mode;
            profile.submode = frame
                .parameters
                .get("submode")
                .and_then(Value::as_str)
                .map(str::to_owned);
            RuntimeCommand::ConfigureRx(profile)
        }
        action
            if matches!(
                action,
                "digi.prepare"
                    | "digi.sstv.prepare"
                    | "digi.arm"
                    | "digi.send"
                    | "digi.sequence.start"
            ) =>
        {
            return Ok(CommandReply {
                ok: false,
                code: "TX_LOCKED".into(),
            })
        }
        _ => {
            return Ok(CommandReply {
                ok: false,
                code: "UNSUPPORTED_NATIVE_COMMAND".into(),
            })
        }
    };
    let result = call_named_state(state, command, frame.command_id, frame.expected_generation)?;
    Ok(CommandReply {
        ok: result.ok,
        code: result.code,
    })
}
#[tauri::command]
fn digi_submit_command(
    state: State<'_, AppState>,
    frame: CommandFrame,
) -> Result<CommandReply, String> {
    submit_backend(state.backend.as_ref(), frame)
}
fn emergency_backend(state: &Backend, scope: StopScope) -> CommandReply {
    if !valid_id(&scope.command_id) {
        return CommandReply {
            ok: false,
            code: "INVALID_COMMAND".into(),
        };
    }
    let targeted = scope
        .agent_id
        .as_deref()
        .map(|v| v == AGENT_ID)
        .unwrap_or(true)
        && scope
            .device_id
            .as_deref()
            .map(|v| v == DEVICE_ID)
            .unwrap_or(true);
    let _generation_advisory = scope.expected_generation;
    CommandReply {
        ok: targeted && state.emergency.trip(),
        code: if targeted {
            "RX_UNCONFIRMED".into()
        } else {
            "INVALID_TARGET".into()
        },
    }
}

fn native_agent_payload(contact: &ReviewedContact) -> Value {
    json!({
        "profileId": contact.profile_id,
        "operationIdentity": contact.operation_identity,
        "sourceRevision": contact.source_revision,
        "capturedUtc": contact.captured_utc,
        "destinationAuthority": contact.destination_authority,
        "authorityRevision": contact.authority_revision,
        "mappingRevision": contact.mapping_revision,
        "stationProfileId": contact.station_profile_id,
        "provenance": contact.provenance,
        "fixture": contact.fixture,
        "contact": contact.contact
    })
}

fn retry_pending_contacts(backend: &Backend) {
    let pending = backend.runtime.lock().ok().and_then(|mut runtime| {
        runtime
            .request(RuntimeCommand::PendingReviewedContacts)
            .ok()
            .and_then(|result| serde_json::from_value::<Vec<ReviewedContact>>(result.payload).ok())
    });
    for contact in pending.unwrap_or_default().into_iter().take(8) {
        let receipt = agent_ingress::submit(&contact.event_id, &native_agent_payload(&contact));
        if receipt.get("durableHandoff") != Some(&Value::Bool(true)) {
            break;
        }
        let _ = backend.runtime.lock().ok().and_then(|mut runtime| {
            runtime
                .request(RuntimeCommand::AcknowledgeContact {
                    event_id: contact.event_id,
                    durable_receipt: true,
                })
                .ok()
        });
    }
}
#[tauri::command]
fn digi_emergency_stop(state: State<'_, AppState>, scope: StopScope) -> CommandReply {
    emergency_backend(state.backend.as_ref(), scope)
}
fn completed_backend(backend: &Backend, intent: CompletedContactIntent) -> Value {
    if backend
        .agent_state
        .lock()
        .map(|s| s.as_str() == "LEGACY_AGENT_HANDOVER_REQUIRED")
        .unwrap_or(true)
    {
        return json!({"state":"PENDING","providerState":"NOT_SENT","code":"LEGACY_AGENT_HANDOVER_REQUIRED","retryable":false});
    }
    retry_pending_contacts(backend);
    let valid = intent.completed
        && intent.user_authorized
        && !intent.station_profile_id.is_empty()
        && !intent.radio_device_id.is_empty()
        && !intent.operation_identity.is_empty()
        && valid_id(&intent.station_profile_id)
        && valid_id(&intent.radio_device_id)
        && intent.radio_device_id == DEVICE_ID
        && valid_id(&intent.operation_identity)
        && intent.qso.is_object()
        && bounded_json(&intent, agent_ingress::MAX_PAYLOAD);
    if !valid {
        return json!({"state":"REJECTED","providerState":"NOT_SENT","code":"NATIVE_CONTACT_INVALID"});
    }
    let binding = agent_ingress::binding(&intent.operation_identity);
    if !binding.get("ok").and_then(Value::as_bool).unwrap_or(false) {
        let code = binding
            .get("code")
            .and_then(Value::as_str)
            .unwrap_or("AGENT_NATIVE_INGRESS_UNAVAILABLE");
        return json!({"state":"NEEDS_ACCOUNT_BINDING","providerState":"NOT_SENT","code":code,"retryable":true});
    }
    if binding.get("stationProfileId") != Some(&Value::String(intent.station_profile_id.clone())) {
        return json!({"state":"REJECTED","providerState":"NOT_SENT","code":"LOGGER_DESTINATION_CHANGED"});
    }
    let candidate = match reviewed_contact_from_intent(
        &intent,
        &binding,
        backend.review.as_ref(),
        chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
    ) {
        Ok(value) => value,
        Err(code) => {
            return json!({"state":"REJECTED","providerState":"NOT_SENT","code":code,"retryable":false})
        }
    };
    let reviewed = match backend.reviewed_operations.lock() {
        Ok(mut operations) => match operations.freeze(&intent.radio_device_id, candidate) {
            Ok(value) => value,
            Err(code) => {
                return json!({"state":"REJECTED","providerState":"NOT_SENT","code":code,"retryable":false})
            }
        },
        Err(_) => {
            return json!({"state":"REJECTED","providerState":"NOT_SENT","code":"NATIVE_CONTACT_OPERATION_STORE_UNAVAILABLE","retryable":false})
        }
    };
    if !bounded_json(&native_agent_payload(&reviewed), agent_ingress::MAX_PAYLOAD) {
        return json!({"state":"REJECTED","providerState":"NOT_SENT","code":"AGENT_NATIVE_INGRESS_INVALID"});
    }
    let queued = match backend.runtime.lock() {
        Ok(mut runtime) => runtime.request(RuntimeCommand::QueueReviewedContact(reviewed.clone())),
        Err(_) => {
            return json!({"state":"REJECTED","providerState":"NOT_SENT","code":"RUNTIME_UNAVAILABLE"})
        }
    };
    let Ok(queued) = queued else {
        return json!({"state":"REJECTED","providerState":"NOT_SENT","code":"QUEUE_WRITE_FAILED"});
    };
    if !queued.ok {
        return json!({"state":"REJECTED","providerState":"NOT_SENT","code":queued.code});
    }
    let event_id = queued.payload.as_str().unwrap_or_default().to_owned();
    let mut result = agent_ingress::submit(&event_id, &native_agent_payload(&reviewed));
    result["eventId"] = Value::String(event_id.clone());
    if result.get("durableHandoff") == Some(&Value::Bool(true)) {
        let receipt = backend.runtime.lock().ok().and_then(|mut runtime| {
            runtime
                .request(RuntimeCommand::AcknowledgeContact {
                    event_id,
                    durable_receipt: true,
                })
                .ok()
        });
        if !receipt.map(|value| value.ok).unwrap_or(false) {
            result = json!({"state":"DELIVERY_UNKNOWN","providerState":"AGENT_PENDING","code":"LOCAL_RECEIPT_WRITE_FAILED","retryable":false});
        }
    }
    result
}
#[tauri::command]
fn digi_submit_completed_contact(
    state: State<'_, AppState>,
    intent: CompletedContactIntent,
) -> Value {
    completed_backend(state.backend.as_ref(), intent)
}
#[tauri::command]
fn digi_close_session(state: State<'_, AppState>) -> Result<(), String> {
    state.backend.emergency.trip();
    state.loopback.disable()?;
    state
        .backend
        .browser_local_available
        .store(false, Ordering::Release);
    Ok(())
}
#[tauri::command]
fn digi_set_browser_local_enabled(
    state: State<'_, AppState>,
    enabled: bool,
) -> Result<Value, String> {
    if state.backend.review.is_some() {
        return Err("BROWSER_LOCAL_DISABLED_IN_ISOLATED_REVIEW".into());
    }
    if enabled {
        let pairing_code = state.loopback.enable(Some(state.backend.clone()))?;
        state
            .backend
            .browser_local_available
            .store(true, Ordering::Release);
        Ok(
            json!({"enabled":true,"state":if pairing_code.is_some(){"PAIRING_REQUIRED"}else{"ALREADY_ENABLED"},"pairingCode":pairing_code,"endpoint":"http://127.0.0.1:17654"}),
        )
    } else {
        state.loopback.disable()?;
        state
            .backend
            .browser_local_available
            .store(false, Ordering::Release);
        Ok(json!({"enabled":false,"state":"DISABLED","pairingCode":null,"endpoint":null}))
    }
}

fn main() {
    let app = tauri::Builder::default()
        .setup(|app| {
            let review_profile = load_review_profile(app.handle())?;
            let emergency = Arc::new(EmergencyStop {
                pid: AtomicU32::new(0),
            });
            let runtime =
                RuntimeSupervisor::spawn(app.handle(), &emergency.pid, review_profile.as_ref())?;
            let browser_local_available = Arc::new(AtomicBool::new(false));
            let (agent, agent_state) =
                AgentSupervisor::ensure(app.handle(), review_profile.as_ref())?;
            let review = review_profile.as_ref().map(|profile| ReviewContext {
                enabled: true,
                label: "SYNTHETIC REVIEW CONTACT — DISPOSABLE ENVIRONMENT",
                origin: profile.origin.clone(),
                instance_id: profile.instance_id.clone(),
            });
            let backend = Arc::new(Backend {
                runtime: Arc::new(Mutex::new(runtime)),
                emergency,
                browser_local_available: browser_local_available.clone(),
                agent_state: Mutex::new(agent_state),
                review,
                reviewed_operations: Mutex::new(FrozenReviewedOperations::default()),
            });
            retry_pending_contacts(backend.as_ref());
            let state = AppState {
                backend,
                loopback: Arc::new(loopback::Controller::new()),
                agent: Mutex::new(agent),
            };
            app.manage(state);
            if let Some(milliseconds) = package_acceptance_exit_ms() {
                let handle = app.handle().clone();
                std::thread::spawn(move || {
                    std::thread::sleep(Duration::from_millis(milliseconds));
                    handle.exit(0);
                });
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            digi_list_targets,
            digi_read_presence,
            digi_submit_command,
            digi_configure_audio,
            digi_emergency_stop,
            digi_submit_completed_contact,
            digi_close_session,
            digi_set_browser_local_enabled,
            digi_read_agent_setup,
            digi_read_review_context,
            digi_prepare_review_contact,
            digi_pair_agent,
            digi_unpair_agent,
            digi_decode_recording
        ])
        .build(tauri::generate_context!())
        .expect("ShackCQ Desktop runtime failed");
    app.run(|handle, event| {
        if matches!(event, tauri::RunEvent::Exit) {
            handle
                .state::<AppState>()
                .agent
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .shutdown();
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use shackcq_nexus_runtime::{ContactQueue, QueueKey};

    fn reviewed_intent(operation_identity: &str) -> CompletedContactIntent {
        CompletedContactIntent {
            station_profile_id: "22222222-2222-4222-8222-222222222222".into(),
            radio_device_id: DEVICE_ID.into(),
            operation_identity: operation_identity.into(),
            completed: true,
            user_authorized: true,
            qso: json!({
                "callsign":"K1ABC",
                "mode":"FT8",
                "submode":Value::Null,
                "frequencyHz":14_074_000,
                "contactStartUtc":"2026-09-13T06:00:00.000Z",
                "comment":"SYNTHETIC REVIEW CONTACT — DISPOSABLE ENVIRONMENT"
            }),
        }
    }

    fn reviewed_binding(authority: &str) -> Value {
        json!({
            "profileId":"11111111-1111-4111-8111-111111111111",
            "sourceRevision":1,
            "accountId":"account-one",
            "stationProfileId":"22222222-2222-4222-8222-222222222222",
            "destinationAuthority":authority,
            "authorityRevision":1,
            "mappingRevision":Value::Null
        })
    }

    fn review_context() -> ReviewContext {
        ReviewContext {
            enabled: true,
            label: "SYNTHETIC REVIEW CONTACT — DISPOSABLE ENVIRONMENT",
            origin: ISOLATED_REVIEW_ORIGIN.into(),
            instance_id: "fixture-instance".into(),
        }
    }
    #[test]
    fn agent_shutdown_is_idempotent_without_an_owned_child() {
        let mut agent = AgentSupervisor {
            child: None,
            owner_token: String::new(),
        };
        agent.shutdown();
        agent.shutdown();
        assert!(!agent.owns_child());
    }
    #[test]
    fn isolated_review_origin_never_falls_back_to_production() {
        assert_eq!(ISOLATED_REVIEW_ORIGIN, "https://localhost:18443");
        assert_ne!(ISOLATED_REVIEW_ORIGIN, PRODUCTION_ORIGIN);
    }
    #[test]
    fn caller_command_id_and_generation_are_preserved() {
        let e = make_envelope("n", RuntimeCommand::Snapshot, "web-command-7".into(), 42);
        assert_eq!(e.command_id, "web-command-7");
        assert_eq!(e.generation, 42);
    }
    #[test]
    fn stale_non_stop_fails_but_stop_is_generation_exempt() {
        assert!(!generation_matches(9, 8, false));
        assert!(generation_matches(9, 8, true));
    }

    #[test]
    fn local_recording_import_is_advertised_after_runtime_command_is_wired() {
        assert!(RECORDING_LOCAL_ONLY);
        assert!(valid_recording_input(&RecordingInput {
            name: "ft8.wav".into(),
            mode: DigiMode::Ft8,
            bytes_base64: "A".repeat(64),
        }));
        assert!(!valid_recording_input(&RecordingInput {
            name: "ft8.wav".into(),
            mode: DigiMode::Ft8,
            bytes_base64: "A".repeat(((MAX_RECORDING_BYTES + 2) / 3) * 4 + 1),
        }));
    }

    #[test]
    fn agent_targets_are_derived_from_trusted_status_and_binding() {
        let status = json!({"cloudAgent":{"paired":true,"stationProfileId":"station-1","stationLabel":"Home station · VK8ABC"}});
        let binding = json!({"ok":true,"stationProfileId":"station-1"});
        let targets = targets_from_agent(&status, &binding);
        assert_eq!(targets["agents"][0]["stationProfileId"], "station-1");
        assert_eq!(targets["agents"][0]["canonicalBindingReady"], true);
        assert_eq!(targets["stations"][0]["callsign"], "VK8ABC");
        assert_eq!(targets["stations"][0]["gridLocator"], Value::Null);
    }

    #[test]
    fn agent_pairing_input_is_strictly_bounded() {
        assert!(valid_pairing_input(&AgentPairingInput {
            code: "ABCD-EFGH-IJKL".into(),
            name: "Home shack".into(),
        }));
        assert!(!valid_pairing_input(&AgentPairingInput {
            code: "too-short".into(),
            name: "Home shack".into(),
        }));
        assert!(!valid_pairing_input(&AgentPairingInput {
            code: "ABCD-EFGH-IJKL".into(),
            name: "x".repeat(81),
        }));
    }

    #[cfg(unix)]
    #[test]
    fn recording_temp_file_is_owner_only() {
        use std::os::unix::fs::PermissionsExt;

        let mut random = [0u8; 16];
        getrandom::fill(&mut random).unwrap();
        let path = std::env::temp_dir().join(format!(
            "shackcq-recording-permissions-{}",
            hex::encode(random)
        ));
        let recording = write_private_recording(&path, b"RIFF").unwrap();
        assert_eq!(
            std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
            0o600
        );
        drop(recording);
        assert!(!path.exists());

        std::fs::write(&path, b"existing").unwrap();
        assert!(write_private_recording(&path, b"replacement").is_err());
        assert_eq!(std::fs::read(&path).unwrap(), b"existing");
        std::fs::remove_file(&path).unwrap();
    }

    #[test]
    fn actual_review_contact_normalizes_and_survives_encrypted_queue_restart() {
        let contact = reviewed_contact_from_intent(
            &reviewed_intent("review-contact-fixture-instance"),
            &reviewed_binding("WEB_LOCAL"),
            Some(&review_context()),
            "2026-09-14T00:00:00.000Z".into(),
        )
        .unwrap();
        assert_eq!(contact.provenance, "NEXUS_NATIVE");
        assert!(contact.fixture);
        assert_eq!(contact.contact["submode"], Value::Null);
        assert_eq!(
            contact.contact["adif"]["COMMENT"],
            "SYNTHETIC REVIEW CONTACT — DISPOSABLE ENVIRONMENT"
        );
        assert_eq!(
            contact.contact["adif"][REVIEW_FIXTURE_ADIF_KEY],
            REVIEW_FIXTURE_ADIF_VALUE
        );
        let checked: Value = serde_json::from_str(include_str!(
            "../../tests/fixtures/reviewed-contact-contract-v1.json"
        ))
        .unwrap();
        assert_eq!(
            native_agent_payload(&contact),
            checked["normalizedAgentPayload"]
        );

        let path = std::env::temp_dir().join(format!(
            "shackcq-reviewed-contact-{}.bin",
            hex::encode(Sha256::digest(contact.operation_identity.as_bytes()))
        ));
        let _ = std::fs::remove_file(&path);
        let key = QueueKey::from_bytes([7; 32]);
        let event_id = {
            let mut queue = ContactQueue::open(&path, key.clone()).unwrap();
            queue.enqueue(contact.clone()).unwrap()
        };
        let restarted = ContactQueue::open(&path, key).unwrap();
        assert_eq!(restarted.len(), 1);
        assert_eq!(restarted.pending()[0].event_id, event_id);
        assert!(restarted.pending()[0].fixture);
        assert_eq!(
            restarted.pending()[0].contact["adif"][REVIEW_FIXTURE_ADIF_KEY],
            REVIEW_FIXTURE_ADIF_VALUE
        );
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn ordinary_contacts_remain_native_and_review_fixture_requires_local_context() {
        let ordinary = reviewed_contact_from_intent(
            &reviewed_intent("ordinary-contact-1"),
            &reviewed_binding("WEB_LOCAL"),
            None,
            "2026-09-14T00:00:00.000Z".into(),
        )
        .unwrap();
        assert_eq!(ordinary.provenance, "NEXUS_NATIVE");
        assert!(!ordinary.fixture);
        assert_eq!(
            ordinary.contact["adif"]["COMMENT"],
            reviewed_intent("x").qso["comment"]
        );
        assert_eq!(
            ordinary.contact["adif"][REVIEW_FIXTURE_ADIF_KEY],
            Value::Null
        );

        assert_eq!(
            reviewed_contact_from_intent(
                &reviewed_intent("review-contact-fixture-instance"),
                &reviewed_binding("WAVELOG"),
                Some(&review_context()),
                "2026-09-14T00:00:00.000Z".into(),
            )
            .unwrap_err(),
            "REVIEW_FIXTURE_DESTINATION_REJECTED"
        );
        assert_eq!(
            reviewed_contact_from_intent(
                &reviewed_intent("review-contact-untrusted"),
                &reviewed_binding("WEB_LOCAL"),
                None,
                "2026-09-14T00:00:00.000Z".into(),
            )
            .unwrap_err(),
            "REVIEW_FIXTURE_CONTEXT_REJECTED"
        );
    }

    #[test]
    fn reviewed_operation_freezes_capture_and_rejects_changed_body() {
        let first = reviewed_contact_from_intent(
            &reviewed_intent("ordinary-contact-1"),
            &reviewed_binding("WEB_LOCAL"),
            None,
            "2026-09-14T00:00:00.000Z".into(),
        )
        .unwrap();
        let later = reviewed_contact_from_intent(
            &reviewed_intent("ordinary-contact-1"),
            &reviewed_binding("WEB_LOCAL"),
            None,
            "2026-09-14T00:10:00.000Z".into(),
        )
        .unwrap();
        let mut frozen = FrozenReviewedOperations::default();
        let accepted = frozen.freeze(DEVICE_ID, first).unwrap();
        let replayed = frozen.freeze(DEVICE_ID, later).unwrap();
        assert_eq!(accepted, replayed);

        let mut changed_intent = reviewed_intent("ordinary-contact-1");
        changed_intent.qso["callsign"] = Value::String("K2XYZ".into());
        let changed = reviewed_contact_from_intent(
            &changed_intent,
            &reviewed_binding("WEB_LOCAL"),
            None,
            "2026-09-14T00:20:00.000Z".into(),
        )
        .unwrap();
        assert_eq!(
            frozen.freeze(DEVICE_ID, changed).unwrap_err(),
            "NATIVE_CONTACT_OPERATION_CHANGED"
        );
    }
}
