// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 KD9TAW <kd9taw@protonmail.com>
// Copyright (C) 2026 Oliver Bross
// Derived from Nexus crates/tempo-net/src/flexdisc.rs at the commit in UPSTREAM.md.

use socket2::{Domain, Protocol, Socket, Type};
use std::collections::BTreeMap;
use std::io;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::time::{Duration, Instant};

pub const DISCOVERY_PORT: u16 = 4992;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DiscoveryRecord {
    pub model: String,
    pub nickname: String,
    pub ip: String,
    pub port: u16,
    pub serial: String,
    pub callsign: String,
    pub version: String,
    pub status: String,
    pub gui_client_handles: String,
    pub gui_client_programs: String,
    pub gui_client_stations: String,
}

fn field<'a>(text: &'a str, key: &str) -> Option<&'a str> {
    let marker = format!("{key}=");
    let start = text.find(&marker)? + marker.len();
    let value = &text[start..];
    Some(
        &value[..value
            .find(|c: char| c.is_whitespace() || c == '\0')
            .unwrap_or(value.len())],
    )
}

pub fn parse_discovery(datagram: &[u8]) -> Option<DiscoveryRecord> {
    let text = String::from_utf8_lossy(datagram);
    let ip = field(&text, "ip")?.parse::<Ipv4Addr>().ok()?.to_string();
    let serial = field(&text, "serial").unwrap_or_default().to_string();
    Some(DiscoveryRecord {
        model: field(&text, "model").unwrap_or("FLEX").to_string(),
        nickname: field(&text, "nickname")
            .or_else(|| field(&text, "name"))
            .unwrap_or_default()
            .to_string(),
        ip,
        port: field(&text, "port")
            .and_then(|v| v.parse().ok())
            .unwrap_or(DISCOVERY_PORT),
        serial,
        callsign: field(&text, "callsign").unwrap_or_default().to_string(),
        version: field(&text, "version").unwrap_or_default().to_string(),
        status: field(&text, "status").unwrap_or_default().to_string(),
        gui_client_handles: field(&text, "gui_client_handles")
            .unwrap_or_default()
            .to_string(),
        gui_client_programs: field(&text, "gui_client_programs")
            .unwrap_or_default()
            .to_string(),
        gui_client_stations: field(&text, "gui_client_stations")
            .unwrap_or_default()
            .to_string(),
    })
}

pub fn discovery_socket() -> io::Result<UdpSocket> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    socket.set_broadcast(true)?;
    socket
        .bind(&SocketAddr::from(([0, 0, 0, 0], DISCOVERY_PORT)).into())
        .map_err(|error| {
            if error.kind() == io::ErrorKind::AddrInUse {
                io::Error::new(
                    io::ErrorKind::AddrInUse,
                    "Flex discovery port 4992 is held by a non-sharing application",
                )
            } else {
                error
            }
        })?;
    let socket: UdpSocket = socket.into();
    socket.set_read_timeout(Some(Duration::from_millis(350)))?;
    Ok(socket)
}

pub fn discover_for(duration: Duration) -> io::Result<Vec<DiscoveryRecord>> {
    let socket = discovery_socket()?;
    let deadline =
        Instant::now() + duration.clamp(Duration::from_millis(350), Duration::from_secs(10));
    let mut records = BTreeMap::<String, DiscoveryRecord>::new();
    let mut buf = [0_u8; 4096];
    while Instant::now() < deadline {
        match socket.recv_from(&mut buf) {
            Ok((count, _)) => {
                if let Some(record) = parse_discovery(&buf[..count]) {
                    let key = if record.serial.is_empty() {
                        record.ip.clone()
                    } else {
                        record.serial.clone()
                    };
                    records.insert(key, record);
                }
            }
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                ) => {}
            Err(error) => return Err(error),
        }
    }
    Ok(records.into_values().collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_full_discovery_identity() {
        let mut packet = vec![0x38, 0x5b, 0x2f, 0x02, 0, 0, 1, 0x1c];
        packet.extend_from_slice(b"model=FLEX-8400 serial=1234 version=3.8.23 nickname=Remote callsign=OM0RX ip=192.168.1.20 port=4992 status=Available gui_client_handles=0xA gui_client_programs=SmartSDR gui_client_stations=Shack");
        let value = parse_discovery(&packet).unwrap();
        assert_eq!(value.model, "FLEX-8400");
        assert_eq!(value.serial, "1234");
        assert_eq!(value.callsign, "OM0RX");
        assert_eq!(value.port, 4992);
        assert_eq!(value.gui_client_stations, "Shack");
    }

    #[test]
    fn rejects_non_discovery_and_invalid_ip() {
        assert!(parse_discovery(b"GET / HTTP/1.1").is_none());
        assert!(parse_discovery(b"model=FAKE ip=not.an.ip.addr").is_none());
    }
}
