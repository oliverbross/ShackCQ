// SPDX-License-Identifier: GPL-3.0-only
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use hmac::{Hmac, Mac};
use serde::Serialize;
use serde_json::{json, Value};
use sha2::Sha256;
use std::io::{BufRead, BufReader, Read, Write};
use std::time::Duration;
#[cfg(windows)]
use std::time::Instant;

const SERVICE: &str = "app.shackcq.desktop";
const ALIAS: &str = "shackcq-native-ingress-v1";
const ACTION: &str = "nexus-contact.submit";
const PREFIX: &[u8] = b"shackcq-native-ingress-v1\0";
const MAX_REQUEST: usize = 16 * 1024;
const MAX_RESPONSE: usize = 16 * 1024;
pub(crate) const MAX_PAYLOAD: usize = 10 * 1024;

pub(crate) fn admin_socket_name() -> String {
    std::env::var("SHACKCQ_AGENT_ADMIN_SOCKET")
        .ok()
        .filter(|value| {
            !value.is_empty()
                && value.len() <= 96
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte))
        })
        .unwrap_or_else(|| "shackcq-stationd-v1".into())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AgentProbe {
    Missing,
    Legacy,
    NativeIngress,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Envelope<'a> {
    action: &'a str,
    protocol: Contract,
    request_id: &'a str,
    nonce: String,
    sent_utc: String,
    payload_base64: String,
    mac: String,
}

#[derive(Clone, Copy, Serialize)]
struct Contract {
    major: u8,
    minor: u8,
}

fn append_field(out: &mut Vec<u8>, value: &[u8]) {
    out.extend_from_slice(&(value.len() as u32).to_be_bytes());
    out.extend_from_slice(value);
}

fn authenticated_bytes(
    action: &str,
    request_id: &str,
    nonce: &str,
    sent: &str,
    payload: &[u8],
) -> Vec<u8> {
    let mut bytes = PREFIX.to_vec();
    append_field(&mut bytes, action.as_bytes());
    append_field(&mut bytes, b"1");
    append_field(&mut bytes, b"0");
    append_field(&mut bytes, request_id.as_bytes());
    append_field(&mut bytes, nonce.as_bytes());
    append_field(&mut bytes, sent.as_bytes());
    append_field(&mut bytes, payload);
    bytes
}

pub fn submit<T: Serialize>(request_id: &str, payload: &T) -> Value {
    classify_result(request(ACTION, request_id, payload))
}

pub fn binding(request_id: &str) -> Value {
    request("native-ingress.binding", request_id, &json!({}))
}

fn request<T: Serialize>(action: &str, request_id: &str, payload: &T) -> Value {
    let payload = match serde_json::to_vec(payload) {
        Ok(v) if v.len() <= MAX_PAYLOAD => v,
        _ => return json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_INVALID"}),
    };
    let secret = match keyring::Entry::new(SERVICE, ALIAS)
        .ok()
        .and_then(|entry| entry.get_password().ok())
        .and_then(|value| hex::decode(value).ok())
        .filter(|value| value.len() == 32)
    {
        Some(value) => value,
        None => return json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_UNAVAILABLE"}),
    };
    let mut nonce_bytes = [0u8; 32];
    if getrandom::fill(&mut nonce_bytes).is_err() {
        return json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_UNAVAILABLE"});
    }
    let nonce = hex::encode(nonce_bytes);
    let sent = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let mut signer = Hmac::<Sha256>::new_from_slice(&secret).expect("32-byte HMAC key");
    signer.update(&authenticated_bytes(
        action, request_id, &nonce, &sent, &payload,
    ));
    let envelope = Envelope {
        action,
        protocol: Contract { major: 1, minor: 0 },
        request_id,
        nonce,
        sent_utc: sent,
        payload_base64: B64.encode(payload),
        mac: hex::encode(signer.finalize().into_bytes()),
    };
    let mut request = match serde_json::to_vec(&envelope) {
        Ok(value) if value.len() < MAX_REQUEST => value,
        _ => return json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_INVALID"}),
    };
    request.push(b'\n');
    transport(request)
}

#[cfg(unix)]
fn transport(request: Vec<u8>) -> Value {
    use std::os::unix::net::UnixStream;
    let path = std::env::temp_dir().join(admin_socket_name());
    let mut stream = match UnixStream::connect(path) {
        Ok(stream) => stream,
        Err(_) => return json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_UNAVAILABLE"}),
    };
    let timeout = Some(Duration::from_secs(3));
    let _ = stream.set_read_timeout(timeout);
    let _ = stream.set_write_timeout(timeout);
    if stream.write_all(&request).is_err() {
        return json!({"ok":false,"code":"AGENT_NATIVE_DELIVERY_UNKNOWN"});
    }
    let mut response = Vec::new();
    if BufReader::new(stream)
        .take((MAX_RESPONSE + 1) as u64)
        .read_until(b'\n', &mut response)
        .is_err()
        || response.len() > MAX_RESPONSE
    {
        return json!({"ok":false,"code":"AGENT_NATIVE_DELIVERY_UNKNOWN"});
    }
    classify_response(&response)
}

#[cfg(not(any(unix, windows)))]
fn transport(_: Vec<u8>) -> Value {
    json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_UNAVAILABLE"})
}

#[cfg(windows)]
fn transport(request: Vec<u8>) -> Value {
    plain_request(&request)
        .and_then(|value| value.get("result").cloned())
        .unwrap_or_else(|| json!({"ok":false,"code":"AGENT_NATIVE_DELIVERY_UNKNOWN"}))
}

fn classify_response(bytes: &[u8]) -> Value {
    serde_json::from_slice::<Value>(bytes)
        .ok()
        .and_then(|value| value.get("result").cloned())
        .unwrap_or_else(|| json!({"ok":false,"code":"AGENT_NATIVE_DELIVERY_UNKNOWN"}))
}

#[cfg(unix)]
fn plain_request(request: &[u8]) -> Option<Value> {
    use std::os::unix::net::UnixStream;
    let mut stream = UnixStream::connect(std::env::temp_dir().join(admin_socket_name())).ok()?;
    let timeout = Some(Duration::from_millis(750));
    stream.set_read_timeout(timeout).ok()?;
    stream.set_write_timeout(timeout).ok()?;
    stream.write_all(request).ok()?;
    let mut response = Vec::new();
    BufReader::new(stream)
        .take((MAX_RESPONSE + 1) as u64)
        .read_until(b'\n', &mut response)
        .ok()?;
    (response.len() <= MAX_RESPONSE)
        .then(|| serde_json::from_slice::<Value>(&response).ok())
        .flatten()
}

#[cfg(windows)]
fn plain_request(request: &[u8]) -> Option<Value> {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE};
    use windows_sys::Win32::Storage::FileSystem::{
        CreateFileW, ReadFile, WriteFile, FILE_ATTRIBUTE_NORMAL, GENERIC_READ, GENERIC_WRITE,
        OPEN_EXISTING,
    };
    use windows_sys::Win32::System::Pipes::{PeekNamedPipe, WaitNamedPipeW};

    let pipe_path = format!(r"\\.\pipe\{}", admin_socket_name());
    let path = OsStr::new(&pipe_path)
        .encode_wide()
        .chain(Some(0))
        .collect::<Vec<_>>();
    unsafe {
        if WaitNamedPipeW(path.as_ptr(), 3_000) == 0 {
            return None;
        }
        let handle = CreateFileW(
            path.as_ptr(),
            GENERIC_READ | GENERIC_WRITE,
            0,
            std::ptr::null(),
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL,
            std::ptr::null_mut(),
        );
        if handle == INVALID_HANDLE_VALUE {
            return None;
        }
        let result = (|| {
            let mut written_total = 0usize;
            while written_total < request.len() {
                let mut written = 0u32;
                if WriteFile(
                    handle,
                    request[written_total..].as_ptr(),
                    (request.len() - written_total).min(u32::MAX as usize) as u32,
                    &mut written,
                    std::ptr::null_mut(),
                ) == 0
                    || written == 0
                {
                    return None;
                }
                written_total += written as usize;
            }
            let mut response = Vec::with_capacity(1024);
            let deadline = Instant::now() + Duration::from_secs(3);
            loop {
                let mut available = 0u32;
                if PeekNamedPipe(
                    handle,
                    std::ptr::null_mut(),
                    0,
                    std::ptr::null_mut(),
                    &mut available,
                    std::ptr::null_mut(),
                ) == 0
                {
                    return None;
                }
                if available == 0 {
                    if Instant::now() >= deadline {
                        return None;
                    }
                    std::thread::sleep(Duration::from_millis(10));
                    continue;
                }
                let mut buffer = [0u8; 1024];
                let mut read = 0u32;
                if ReadFile(
                    handle,
                    buffer.as_mut_ptr(),
                    available.min(buffer.len() as u32),
                    &mut read,
                    std::ptr::null_mut(),
                ) == 0
                    || read == 0
                {
                    return None;
                }
                response.extend_from_slice(&buffer[..read as usize]);
                if response.len() > MAX_RESPONSE {
                    return None;
                }
                if let Some(end) = response.iter().position(|byte| *byte == b'\n') {
                    response.truncate(end + 1);
                    return serde_json::from_slice(&response).ok();
                }
            }
        })();
        CloseHandle(handle);
        result
    }
}

#[cfg(any(unix, windows))]
pub fn setup_request(action: &str, owner_token: &str, fields: Value) -> Value {
    let mut request = serde_json::Map::new();
    request.insert("action".into(), Value::String(action.into()));
    request.insert("ownerToken".into(), Value::String(owner_token.into()));
    if let Some(extra) = fields.as_object() {
        for (key, value) in extra {
            request.insert(key.clone(), value.clone());
        }
    }
    let mut encoded = match serde_json::to_vec(&Value::Object(request)) {
        Ok(value) if value.len() < MAX_REQUEST => value,
        _ => return json!({"ok":false,"code":"AGENT_SETUP_INVALID"}),
    };
    encoded.push(b'\n');
    plain_request(&encoded)
        .and_then(|value| value.get("result").cloned())
        .unwrap_or_else(|| json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_UNAVAILABLE"}))
}

#[cfg(not(any(unix, windows)))]
pub fn setup_request(_: &str, _: &str, _: Value) -> Value {
    json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_UNAVAILABLE"})
}

pub fn setup_status() -> Value {
    #[cfg(any(unix, windows))]
    {
        return plain_request(b"{\"action\":\"status\"}\n")
            .and_then(|value| value.get("result").cloned())
            .unwrap_or_else(|| json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_UNAVAILABLE"}));
    }
    #[cfg(not(any(unix, windows)))]
    json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_UNAVAILABLE"})
}

#[cfg(any(unix, windows))]
pub fn probe() -> AgentProbe {
    let Some(value) = plain_request(b"{\"action\":\"status\"}\n") else {
        return AgentProbe::Missing;
    };
    let native = value
        .pointer("/result/nativeIngress/contract/major")
        .and_then(Value::as_u64)
        == Some(1);
    if native {
        AgentProbe::NativeIngress
    } else {
        AgentProbe::Legacy
    }
}

#[cfg(not(any(unix, windows)))]
pub fn probe() -> AgentProbe {
    AgentProbe::Missing
}

pub fn request_owned_stop(owner_token: &str) {
    #[cfg(any(unix, windows))]
    let _ = plain_request(
        format!("{{\"action\":\"stop\",\"ownerToken\":\"{owner_token}\"}}\n").as_bytes(),
    );
}

fn classify_result(result: Value) -> Value {
    let code = result
        .get("code")
        .and_then(Value::as_str)
        .unwrap_or("AGENT_NATIVE_DELIVERY_UNKNOWN");
    match code {
        "LOGGER_NATIVE_QUEUED" | "LOGGER_NATIVE_DUPLICATE" => {
            json!({"state":"AGENT_DURABLE_PENDING","providerState":"AGENT_PENDING","code":code,"durableHandoff":true})
        }
        "AGENT_NATIVE_INGRESS_UNAVAILABLE" => {
            json!({"state":"PENDING","providerState":"NOT_SENT","code":code,"retryable":true})
        }
        "AGENT_NATIVE_INGRESS_AUTH_FAILED" | "AGENT_NATIVE_INGRESS_INVALID" => {
            json!({"state":"REJECTED","providerState":"NOT_SENT","code":code,"retryable":false})
        }
        "LOGGER_DESTINATION_CHANGED"
        | "LOGGER_EVENT_ID_REUSED"
        | "LOGGER_NATIVE_PROFILE_UNAVAILABLE" => {
            json!({"state":"REJECTED","providerState":"NOT_SENT","code":code,"retryable":false})
        }
        _ => {
            json!({"state":"DELIVERY_UNKNOWN","providerState":"UNKNOWN","code":code,"retryable":false})
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn receipts_distinguish_retryable_terminal_and_ambiguous() {
        assert_eq!(
            classify_result(classify_response(
                br#"{"result":{"code":"AGENT_NATIVE_INGRESS_UNAVAILABLE"}}"#
            ))["retryable"],
            true
        );
        assert_eq!(
            classify_result(classify_response(
                br#"{"result":{"code":"LOGGER_DESTINATION_CHANGED"}}"#
            ))["state"],
            "REJECTED"
        );
        assert_eq!(
            classify_result(classify_response(b"not-json"))["state"],
            "DELIVERY_UNKNOWN"
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_named_pipe_reaches_the_isolated_qt_agent() {
        if std::env::var("SHACKCQ_TEST_WINDOWS_AGENT_PIPE").as_deref() != Ok("1") {
            return;
        }
        let owner_token = std::env::var("SHACKCQ_TEST_WINDOWS_AGENT_OWNER_TOKEN")
            .expect("isolated Agent owner token");
        assert_eq!(probe(), AgentProbe::NativeIngress);
        assert_eq!(
            setup_request("native-setup.unpair", &"0".repeat(64), json!({}))["code"],
            "OWNER_TOKEN_REJECTED"
        );
        let stopped = setup_request("stop", &owner_token, json!({}));
        assert_eq!(stopped["code"], "GLOBAL_STOPPED");
        assert_eq!(stopped["stopped"], true);
    }
}
