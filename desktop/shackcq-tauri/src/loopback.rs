// SPDX-License-Identifier: GPL-3.0-only
use super::*;
use std::collections::VecDeque;
use std::io::Read;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use subtle::ConstantTimeEq;
use tiny_http::{Header, Method, Response, Server, StatusCode};

const ORIGIN: &str = "https://shackcq.com";
const AUTH_PREFIX: &str = "ShackCQ-Local ";
const MAX_BODY: u64 = 128 * 1024;

struct Grant {
    token: [u8; 32],
    expires: Instant,
}
struct Access {
    grant: Option<Grant>,
    pairing: Option<[u8; 16]>,
    generation: u64,
    attempts: VecDeque<Instant>,
}
struct Running {
    server: Arc<Server>,
    shutdown: Arc<AtomicBool>,
    access: Arc<Mutex<Access>>,
    joins: Vec<std::thread::JoinHandle<()>>,
}
pub struct Controller {
    running: Mutex<Option<Running>>,
}
impl Controller {
    pub fn new() -> Self {
        Self {
            running: Mutex::new(None),
        }
    }
    pub fn enabled(&self) -> bool {
        self.running.lock().map(|v| v.is_some()).unwrap_or(false)
    }
    pub fn enable(&self, state: Option<Arc<Backend>>) -> Result<Option<String>, String> {
        let mut slot = self
            .running
            .lock()
            .map_err(|_| "browser-local state unavailable")?;
        if slot.is_some() {
            return Ok(None);
        }
        let server = Arc::new(
            Server::http("127.0.0.1:17654").map_err(|_| "browser-local loopback bind failed")?,
        );
        let mut pairing = [0u8; 16];
        getrandom::fill(&mut pairing).map_err(|_| "pairing code unavailable")?;
        let access = Arc::new(Mutex::new(Access {
            grant: None,
            pairing: Some(pairing),
            generation: 0,
            attempts: VecDeque::new(),
        }));
        let shutdown = Arc::new(AtomicBool::new(false));
        let mut joins = Vec::new();
        let (tx, rx) = crossbeam_channel::bounded::<tiny_http::Request>(8);
        for lane in 0..3 {
            let state = state.clone();
            let access = access.clone();
            let rx = rx.clone();
            let shutdown = shutdown.clone();
            joins.push(
                std::thread::Builder::new()
                    .name(format!("shackcq-browser-local-{lane}"))
                    .spawn(move || {
                        while !shutdown.load(Ordering::Acquire) {
                            if let Ok(request) = rx.recv_timeout(Duration::from_millis(50)) {
                                if let Some(state) = state.as_ref() {
                                    serve_validated(request, state, &access)
                                } else {
                                    respond(
                                        request,
                                        503,
                                        json!({"error":"TEST_BACKEND_UNAVAILABLE"}),
                                    )
                                }
                            }
                        }
                    })
                    .map_err(|_| "browser-local worker failed")?,
            );
        }
        let dispatch_server = server.clone();
        let dispatch_shutdown = shutdown.clone();
        let dispatch_state = state.clone();
        let dispatch_access = access.clone();
        joins.push(
            std::thread::Builder::new()
                .name("shackcq-browser-local-stop-dispatch".into())
                .spawn(move || {
                    while !dispatch_shutdown.load(Ordering::Acquire) {
                        let Ok(request) = dispatch_server.recv() else {
                            break;
                        };
                        let request = match front_gate(request) {
                            Ok(v) => v,
                            Err(()) => continue,
                        };
                        if is_emergency_path(&request) {
                            if let Some(state) = dispatch_state.as_ref() {
                                serve_validated(request, state, &dispatch_access)
                            } else {
                                respond(request, 503, json!({"error":"TEST_BACKEND_UNAVAILABLE"}))
                            }
                        } else if let Err(error) = tx.try_send(request) {
                            respond(error.into_inner(), 503, json!({"error":"LOCAL_QUEUE_FULL"}))
                        }
                    }
                })
                .map_err(|_| "browser-local dispatcher failed")?,
        );
        *slot = Some(Running {
            server,
            shutdown,
            access,
            joins,
        });
        Ok(Some(hex::encode(pairing)))
    }
    pub fn disable(&self) -> Result<(), String> {
        let mut slot = self
            .running
            .lock()
            .map_err(|_| "browser-local state unavailable")?;
        let running = slot.take();
        if let Some(mut run) = running {
            if let Ok(mut access) = run.access.lock() {
                access.grant = None;
                access.pairing = None
            }
            run.shutdown.store(true, Ordering::Release);
            run.server.unblock();
            for join in run.joins.drain(..) {
                let _ = join.join();
            }
        }
        Ok(())
    }
}
impl Drop for Controller {
    fn drop(&mut self) {
        let _ = self.disable();
    }
}
fn is_emergency_path(request: &tiny_http::Request) -> bool {
    request.url().split('?').next() == Some("/v1/digi/emergency-stop")
}
fn header(req: &tiny_http::Request, name: &'static str) -> Option<String> {
    req.headers()
        .iter()
        .find(|h| h.field.equiv(name))
        .map(|h| h.value.as_str().to_string())
}
fn cors(origin: &str) -> Vec<Header> {
    [
        ("Access-Control-Allow-Origin", origin),
        ("Vary", "Origin"),
        ("Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
        (
            "Access-Control-Allow-Headers",
            "Authorization, Content-Type, X-ShackCQ-Origin",
        ),
        ("Access-Control-Allow-Private-Network", "true"),
        ("Cache-Control", "no-store"),
        ("X-Content-Type-Options", "nosniff"),
    ]
    .into_iter()
    .map(|(k, v)| Header::from_bytes(k, v).unwrap())
    .collect()
}
fn respond(req: tiny_http::Request, status: u16, value: Value) {
    let mut encoded = value.to_string();
    let mut status = status;
    if encoded.len() > 128 * 1024 {
        encoded = "{\"error\":\"RESPONSE_TOO_LARGE\"}".into();
        status = 507
    }
    let mut response = Response::from_string(encoded).with_status_code(StatusCode(status));
    response.add_header(Header::from_bytes("Content-Type", "application/json").unwrap());
    for h in cors(ORIGIN) {
        response.add_header(h)
    }
    let _ = req.respond(response);
}
fn authorized(req: &tiny_http::Request, access: &Mutex<Access>) -> bool {
    let Some(raw) =
        header(req, "Authorization").and_then(|v| v.strip_prefix(AUTH_PREFIX).map(str::to_owned))
    else {
        return false;
    };
    let Ok(candidate) = hex::decode(raw) else {
        return false;
    };
    if candidate.len() != 32 {
        return false;
    }
    let guard = access.lock().unwrap();
    let Some(g) = guard.grant.as_ref() else {
        return false;
    };
    g.expires > Instant::now() && candidate.as_slice().ct_eq(&g.token).into()
}
fn body(req: &mut tiny_http::Request) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    req.as_reader()
        .take(MAX_BODY + 1)
        .read_to_end(&mut out)
        .ok()?;
    if out.len() as u64 > MAX_BODY {
        None
    } else {
        Some(out)
    }
}
fn front_gate(req: tiny_http::Request) -> Result<tiny_http::Request, ()> {
    if header(&req, "Host").as_deref() != Some("127.0.0.1:17654")
        || header(&req, "Origin").as_deref() != Some(ORIGIN)
    {
        respond(req, 403, json!({"error":"ORIGIN_OR_HOST_REJECTED"}));
        return Err(());
    }
    if req.method() == &Method::Options {
        let requested = header(&req, "Access-Control-Request-Method");
        let requested_headers = header(&req, "Access-Control-Request-Headers")
            .unwrap_or_default()
            .to_ascii_lowercase();
        if !matches!(requested.as_deref(), Some("GET" | "POST"))
            || requested_headers
                .split(',')
                .map(str::trim)
                .any(|v| !matches!(v, "authorization" | "content-type" | "x-shackcq-origin"))
        {
            respond(req, 403, json!({"error":"PREFLIGHT_REJECTED"}));
            return Err(());
        }
        respond(req, 204, Value::Null);
        return Err(());
    }
    if header(&req, "X-ShackCQ-Origin").as_deref() != Some(ORIGIN) {
        respond(req, 403, json!({"error":"ORIGIN_HEADER_REJECTED"}));
        return Err(());
    }
    if req.method() == &Method::Post
        && !header(&req, "Content-Type")
            .as_deref()
            .is_some_and(|v| v.split(';').next() == Some("application/json"))
    {
        respond(req, 415, json!({"error":"JSON_CONTENT_TYPE_REQUIRED"}));
        return Err(());
    }
    Ok(req)
}
fn serve_validated(mut req: tiny_http::Request, state: &Backend, access: &Mutex<Access>) {
    let method = req.method().clone();
    let path = req.url().split('?').next().unwrap_or("").to_string();
    if path == "/v1/capabilities/approve" && method == Method::Post {
        let Some(bytes) = body(&mut req) else {
            respond(req, 413, json!({"error":"FRAME_TOO_LARGE"}));
            return;
        };
        let Ok(value) = serde_json::from_slice::<Value>(&bytes) else {
            respond(req, 400, json!({"error":"INVALID_JSON"}));
            return;
        };
        if !valid_contract(&value) {
            respond(req, 400, json!({"error":"INVALID_CONTRACT"}));
            return;
        }
        let challenge = value
            .get("challengeId")
            .and_then(Value::as_str)
            .unwrap_or("");
        let origin = value.get("origin").and_then(Value::as_str).unwrap_or("");
        if challenge.len() != 32
            || !challenge
                .bytes()
                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
            || origin != ORIGIN
        {
            respond(req, 400, json!({"error":"INVALID_CHALLENGE"}));
            return;
        }
        let approved =
            {
                let mut a = access.lock().unwrap();
                let now = Instant::now();
                while a
                    .attempts
                    .front()
                    .is_some_and(|t| now.duration_since(*t) > Duration::from_secs(60))
                {
                    a.attempts.pop_front();
                }
                if a.attempts.len() >= 3 {
                    respond(req, 429, json!({"error":"APPROVAL_RATE_LIMIT"}));
                    return;
                }
                a.attempts.push_back(now);
                let decoded = hex::decode(challenge).ok();
                let matched = decoded.as_deref().zip(a.pairing.as_ref()).is_some_and(
                    |(candidate, pairing)| {
                        candidate.len() == 16 && bool::from(candidate.ct_eq(pairing))
                    },
                );
                if matched {
                    a.pairing = None
                }
                matched
            };
        if !approved {
            respond(req, 403, json!({"error":"LOCAL_APPROVAL_DENIED"}));
            return;
        }
        let mut token = [0u8; 32];
        if getrandom::fill(&mut token).is_err() {
            respond(req, 500, json!({"error":"TOKEN_UNAVAILABLE"}));
            return;
        }
        let mut a = access.lock().unwrap();
        a.generation = a.generation.saturating_add(1);
        let generation = a.generation;
        a.grant = Some(Grant {
            token,
            expires: Instant::now() + Duration::from_secs(300),
        });
        respond(
            req,
            200,
            json!({"capability":hex::encode(token),"expiresUtc":(chrono::Utc::now()+chrono::Duration::minutes(5)).to_rfc3339(),"capabilityGeneration":generation}),
        );
        return;
    }
    if !authorized(&req, access) {
        respond(req, 401, json!({"error":"LOCAL_APPROVAL_REQUIRED"}));
        return;
    }
    if path == "/v1/capabilities/revoke" && method == Method::Post {
        let Some(bytes) = body(&mut req) else {
            respond(req, 413, json!({"error":"FRAME_TOO_LARGE"}));
            return;
        };
        let value: Value = match serde_json::from_slice(&bytes) {
            Ok(v) => v,
            Err(_) => {
                respond(req, 400, json!({"error":"INVALID_JSON"}));
                return;
            }
        };
        if !valid_contract(&value) {
            respond(req, 400, json!({"error":"INVALID_CONTRACT"}));
            return;
        }
        access.lock().unwrap().grant = None;
        respond(req, 200, json!({"ok":true}));
        return;
    }
    match (&method, path.as_str()) {
        (&Method::Get, "/v1/digi/targets") => respond(req, 200, list_targets_backend()),
        (&Method::Get, "/v1/digi/state") => {
            let query = req.url().split_once('?').map(|x| x.1).unwrap_or("");
            let mut agent = None;
            let mut device = None;
            for pair in query.split('&') {
                if let Some((k, v)) = pair.split_once('=') {
                    let decoded = urlencoding::decode(v).ok().map(|x| x.into_owned());
                    if k == "agentId" {
                        agent = decoded
                    } else if k == "deviceId" {
                        device = decoded
                    }
                }
            }
            match (agent, device) {
                (Some(agent_id), Some(device_id)) => match presence_backend(
                    state,
                    Target {
                        agent_id,
                        device_id,
                    },
                ) {
                    Ok(v) => respond(req, 200, v),
                    Err(_) => respond(req, 503, json!({"error":"RUNTIME_UNAVAILABLE"})),
                },
                _ => respond(req, 400, json!({"error":"INVALID_TARGET"})),
            }
        }
        (&Method::Post, "/v1/digi/commands") => parse_post::<CommandFrame, _>(req, |v| {
            submit_backend(state, v).map(|x| serde_json::to_value(x).unwrap())
        }),
        (&Method::Post, "/v1/digi/audio-selection") => {
            parse_post::<AudioSelection, _>(req, |v| configure_backend(state, v))
        }
        (&Method::Post, "/v1/digi/emergency-stop") => parse_post::<StopScope, _>(req, |v| {
            Ok(serde_json::to_value(emergency_backend(state, v)).unwrap())
        }),
        (&Method::Post, "/v1/digi/completed-contacts") => {
            let Some(bytes) = body(&mut req) else {
                respond(req, 413, json!({"error":"FRAME_TOO_LARGE"}));
                return;
            };
            let value: Value = match serde_json::from_slice(&bytes) {
                Ok(v) => v,
                Err(_) => {
                    respond(req, 400, json!({"error":"INVALID_JSON"}));
                    return;
                }
            };
            if !valid_contract(&value) {
                respond(req, 400, json!({"error":"INVALID_CONTRACT"}));
                return;
            }
            match serde_json::from_value::<CompletedContactIntent>(
                value.get("intent").cloned().unwrap_or(Value::Null),
            ) {
                Ok(v) => respond(req, 200, completed_backend(state, v)),
                Err(_) => respond(req, 400, json!({"error":"INVALID_CONTACT"})),
            }
        }
        _ => respond(req, 404, json!({"error":"NOT_FOUND"})),
    }
}
fn valid_contract(value: &Value) -> bool {
    value
        .get("contract")
        .is_some_and(|v| v.get("major") == Some(&json!(1)) && v.get("minor") == Some(&json!(0)))
}
fn parse_post<T, F>(mut req: tiny_http::Request, run: F)
where
    T: for<'de> Deserialize<'de>,
    F: FnOnce(T) -> Result<Value, String>,
{
    let Some(bytes) = body(&mut req) else {
        respond(req, 413, json!({"error":"FRAME_TOO_LARGE"}));
        return;
    };
    let value: Value = match serde_json::from_slice(&bytes) {
        Ok(v) => v,
        Err(_) => {
            respond(req, 400, json!({"error":"INVALID_JSON"}));
            return;
        }
    };
    if !valid_contract(&value) {
        respond(req, 400, json!({"error":"INVALID_CONTRACT"}));
        return;
    }
    match serde_json::from_value::<T>(value)
        .ok()
        .and_then(|v| run(v).ok())
    {
        Some(v) => respond(req, 200, v),
        None => respond(req, 400, json!({"error":"INVALID_REQUEST"})),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpStream;
    use std::sync::OnceLock;
    fn fixed_port_guard() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
        LOCK.get_or_init(|| Mutex::new(())).lock().unwrap()
    }
    #[test]
    fn capability_compare_is_exact() {
        let token = [7u8; 32];
        assert!(bool::from(token.ct_eq(&[7u8; 32])));
        assert!(!bool::from(token.ct_eq(&[8u8; 32])))
    }
    fn socket_roundtrip(request: &str) -> String {
        let server = Server::http("127.0.0.1:0").unwrap();
        let address = server.server_addr().to_ip().unwrap();
        let worker = std::thread::spawn(move || {
            front_gate(server.recv().unwrap()).ok();
        });
        let mut client = TcpStream::connect(address).unwrap();
        client.write_all(request.as_bytes()).unwrap();
        client.shutdown(std::net::Shutdown::Write).unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).unwrap();
        worker.join().unwrap();
        response
    }
    fn contract_roundtrip(body: &str) -> String {
        let server = Server::http("127.0.0.1:0").unwrap();
        let address = server.server_addr().to_ip().unwrap();
        let worker = std::thread::spawn(move || {
            if let Ok(req) = front_gate(server.recv().unwrap()) {
                parse_post::<Value, _>(req, |_| Ok(json!({"ok":true})));
            }
        });
        let request=format!("POST /v1/digi/commands HTTP/1.1\r\nHost: 127.0.0.1:17654\r\nOrigin: https://shackcq.com\r\nX-ShackCQ-Origin: https://shackcq.com\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",body.len(),body);
        let mut client = TcpStream::connect(address).unwrap();
        client.write_all(request.as_bytes()).unwrap();
        client.shutdown(std::net::Shutdown::Write).unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).unwrap();
        worker.join().unwrap();
        response
    }
    fn send(
        address: std::net::SocketAddr,
        path: &str,
        body: Option<&str>,
        auth: Option<&str>,
    ) -> String {
        let method = if body.is_some() { "POST" } else { "GET" };
        let payload = body.unwrap_or("");
        let auth = auth
            .map(|v| format!("Authorization: ShackCQ-Local {v}\r\n"))
            .unwrap_or_default();
        let content = if body.is_some() {
            format!(
                "Content-Type: application/json\r\nContent-Length: {}\r\n",
                payload.len()
            )
        } else {
            String::new()
        };
        let request=format!("{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:17654\r\nOrigin: https://shackcq.com\r\nX-ShackCQ-Origin: https://shackcq.com\r\n{auth}{content}Connection: close\r\n\r\n{payload}");
        let mut client = TcpStream::connect(address).unwrap();
        client.write_all(request.as_bytes()).unwrap();
        client.shutdown(std::net::Shutdown::Write).unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).unwrap();
        response
    }
    fn serve_test_backend(req: tiny_http::Request, access: &Mutex<Access>) {
        let mut req = match front_gate(req) {
            Ok(v) => v,
            Err(()) => return,
        };
        let path = req.url().to_string();
        if path == "/approve" {
            let Some(bytes) = body(&mut req) else {
                respond(req, 413, json!({"error":"FRAME_TOO_LARGE"}));
                return;
            };
            let value: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
            if !valid_contract(&value) {
                respond(req, 400, json!({"error":"INVALID_CONTRACT"}));
                return;
            }
            let expired = value.get("expired") == Some(&Value::Bool(true));
            access.lock().unwrap().grant = Some(Grant {
                token: [9; 32],
                expires: if expired {
                    Instant::now() - Duration::from_secs(1)
                } else {
                    Instant::now() + Duration::from_secs(300)
                },
            });
            respond(req, 200, json!({"capability":hex::encode([9u8;32])}));
            return;
        }
        if !authorized(&req, access) {
            respond(req, 401, json!({"error":"LOCAL_APPROVAL_REQUIRED"}));
            return;
        }
        if path == "/revoke" {
            let Some(bytes) = body(&mut req) else {
                respond(req, 413, json!({}));
                return;
            };
            let value: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
            if !valid_contract(&value) {
                respond(req, 400, json!({"error":"INVALID_CONTRACT"}));
                return;
            }
            access.lock().unwrap().grant = None;
            respond(req, 200, json!({"ok":true}));
            return;
        }
        if path == "/command" {
            let Some(bytes) = body(&mut req) else {
                respond(req, 413, json!({}));
                return;
            };
            let value: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
            if value.get("expectedGeneration") != Some(&json!(9)) {
                respond(req, 409, json!({"code":"STALE_GENERATION"}))
            } else {
                respond(req, 200, json!({"ok":true}))
            };
            return;
        }
        respond(req, 200, json!({"ok":true}))
    }
    #[test]
    fn actual_socket_preflight_allows_only_exact_origin_and_headers() {
        let good=socket_roundtrip("OPTIONS /v1/digi/commands HTTP/1.1\r\nHost: 127.0.0.1:17654\r\nOrigin: https://shackcq.com\r\nAccess-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: authorization, content-type, x-shackcq-origin\r\nConnection: close\r\n\r\n");
        assert!(good.starts_with("HTTP/1.1 204"));
        assert!(good.contains("Access-Control-Allow-Origin: https://shackcq.com"));
        let bad=socket_roundtrip("OPTIONS /v1/digi/commands HTTP/1.1\r\nHost: 127.0.0.1:17654\r\nOrigin: https://evil.invalid\r\nAccess-Control-Request-Method: POST\r\nConnection: close\r\n\r\n");
        assert!(bad.starts_with("HTTP/1.1 403"));
    }
    #[test]
    fn actual_socket_rejects_wrong_host_and_missing_json_content_type() {
        let bad=socket_roundtrip("GET /v1/digi/targets HTTP/1.1\r\nHost: localhost:17654\r\nOrigin: https://shackcq.com\r\nX-ShackCQ-Origin: https://shackcq.com\r\nConnection: close\r\n\r\n");
        assert!(bad.starts_with("HTTP/1.1 403"));
        let missing=socket_roundtrip("POST /v1/digi/commands HTTP/1.1\r\nHost: 127.0.0.1:17654\r\nOrigin: https://shackcq.com\r\nX-ShackCQ-Origin: https://shackcq.com\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        assert!(missing.starts_with("HTTP/1.1 415"));
    }
    #[test]
    fn actual_socket_enforces_exact_contract() {
        assert!(
            contract_roundtrip(r#"{"contract":{"major":1,"minor":1}}"#).starts_with("HTTP/1.1 400")
        );
        assert!(
            contract_roundtrip(r#"{"contract":{"major":1,"minor":0}}"#).starts_with("HTTP/1.1 200")
        );
    }
    #[test]
    fn actual_socket_authenticated_lifecycle_expiry_revoke_size_and_stale_generation() {
        let server = Server::http("127.0.0.1:0").unwrap();
        let address = server.server_addr().to_ip().unwrap();
        let worker = std::thread::spawn(move || {
            let access = Mutex::new(Access {
                grant: None,
                pairing: None,
                generation: 0,
                attempts: VecDeque::new(),
            });
            for _ in 0..11 {
                serve_test_backend(server.recv().unwrap(), &access)
            }
        });
        let contract = r#"{"contract":{"major":1,"minor":0}}"#;
        let token = hex::encode([9u8; 32]);
        assert!(send(address, "/approve", Some(contract), None).starts_with("HTTP/1.1 200"));
        assert!(send(address, "/targets", None, Some(&token)).starts_with("HTTP/1.1 200"));
        assert!(send(
            address,
            "/approve",
            Some(r#"{"contract":{"major":1,"minor":0},"expired":true}"#),
            None
        )
        .starts_with("HTTP/1.1 200"));
        assert!(send(address, "/targets", None, Some(&token)).starts_with("HTTP/1.1 401"));
        assert!(send(address, "/approve", Some(contract), None).starts_with("HTTP/1.1 200"));
        assert!(send(address, "/revoke", Some(contract), Some(&token)).starts_with("HTTP/1.1 200"));
        assert!(send(address, "/targets", None, Some(&token)).starts_with("HTTP/1.1 401"));
        assert!(send(
            address,
            "/approve",
            Some(r#"{"contract":{"major":1,"minor":1}}"#),
            None
        )
        .starts_with("HTTP/1.1 400"));
        let oversized = "x".repeat(MAX_BODY as usize + 1);
        assert!(send(address, "/approve", Some(&oversized), None).starts_with("HTTP/1.1 413"));
        send(address, "/approve", Some(contract), None);
        let stale = r#"{"contract":{"major":1,"minor":0},"expectedGeneration":8}"#;
        assert!(send(address, "/command", Some(stale), Some(&token)).starts_with("HTTP/1.1 409"));
        worker.join().unwrap();
    }
    #[test]
    fn actual_socket_emergency_lane_bypasses_blocked_normal_worker() {
        let server = Arc::new(Server::http("127.0.0.1:0").unwrap());
        let address = server.server_addr().to_ip().unwrap();
        let (tx, rx) = crossbeam_channel::bounded(1);
        let normal = std::thread::spawn(move || {
            let req: tiny_http::Request = rx.recv().unwrap();
            std::thread::sleep(Duration::from_millis(300));
            respond(req, 200, json!({"normal":true}));
        });
        let dispatcher = {
            let server = server.clone();
            std::thread::spawn(move || {
                for _ in 0..2 {
                    let req = server.recv().unwrap();
                    if is_emergency_path(&req) {
                        respond(req, 200, json!({"code":"RX_UNCONFIRMED"}))
                    } else {
                        tx.send(req).unwrap()
                    }
                }
            })
        };
        let blocked = std::thread::spawn(move || send(address, "/v1/digi/state", None, None));
        std::thread::sleep(Duration::from_millis(25));
        let started = Instant::now();
        let stop = send(
            address,
            "/v1/digi/emergency-stop",
            Some(r#"{"contract":{"major":1,"minor":0}}"#),
            None,
        );
        assert!(stop.starts_with("HTTP/1.1 200"));
        assert!(started.elapsed() < Duration::from_millis(150));
        blocked.join().unwrap();
        dispatcher.join().unwrap();
        normal.join().unwrap();
    }
    #[test]
    fn controller_defaults_off_enables_idempotently_disables_and_restarts_off() {
        let _guard = fixed_port_guard();
        let controller = Controller::new();
        assert!(!controller.enabled());
        assert!(TcpStream::connect("127.0.0.1:17654").is_err());
        let first = controller.enable(None).unwrap().unwrap();
        assert_eq!(first.len(), 32);
        assert!(first
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()));
        assert!(controller.enabled());
        assert_eq!(controller.enable(None).unwrap(), None);
        assert!(TcpStream::connect("127.0.0.1:17654").is_ok());
        controller.disable().unwrap();
        assert!(!controller.enabled());
        assert!(TcpStream::connect("127.0.0.1:17654").is_err());
        let second = controller.enable(None).unwrap().unwrap();
        assert_ne!(first, second);
        controller.disable().unwrap();
        let restarted = Controller::new();
        assert!(!restarted.enabled());
        assert!(TcpStream::connect("127.0.0.1:17654").is_err());
    }
    #[test]
    fn controller_reports_bind_collision_without_changing_disabled_state() {
        let _guard = fixed_port_guard();
        let collision = std::net::TcpListener::bind("127.0.0.1:17654").unwrap();
        let controller = Controller::new();
        assert!(controller.enable(None).is_err());
        assert!(!controller.enabled());
        drop(collision);
    }
}
