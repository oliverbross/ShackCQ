// SPDX-License-Identifier: GPL-3.0-only
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use hmac::{Hmac, Mac};
use serde::Serialize;
use serde_json::{json, Value};
use sha2::Sha256;
use std::io::{BufRead, BufReader, Read, Write};
use std::time::Duration;

const SERVICE: &str = "app.shackcq.desktop";
const ALIAS: &str = "shackcq-native-ingress-v1";
const ACTION: &str = "nexus-contact.submit";
const PREFIX: &[u8] = b"shackcq-native-ingress-v1\0";
const MAX_REQUEST: usize = 16 * 1024;
const MAX_RESPONSE: usize = 16 * 1024;
pub(crate) const MAX_PAYLOAD: usize = 10 * 1024;

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
    let path = std::env::temp_dir().join("shackcq-stationd-v1");
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

#[cfg(not(unix))]
fn transport(_: Vec<u8>) -> Value {
    json!({"ok":false,"code":"AGENT_NATIVE_INGRESS_UNAVAILABLE"})
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
    let mut stream = UnixStream::connect(std::env::temp_dir().join("shackcq-stationd-v1")).ok()?;
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

#[cfg(unix)]
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

#[cfg(not(unix))]
pub fn probe() -> AgentProbe {
    AgentProbe::Missing
}

pub fn request_owned_stop(owner_token: &str) {
    #[cfg(unix)]
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
}
