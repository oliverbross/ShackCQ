// SPDX-License-Identifier: GPL-3.0-only
use serde_json::Value;
use shackcq_nexus_runtime::{
    CommandEnvelope, CommandResult, QueueKey, StationRuntime, CONTRACT_VERSION, MAX_COMMAND_BYTES,
};
use std::io::{self, BufRead, Write};

fn read_bounded_line<R: BufRead>(
    reader: &mut R,
    maximum: usize,
) -> io::Result<Option<Result<Vec<u8>, ()>>> {
    let mut out = Vec::with_capacity(maximum.min(4096));
    let mut oversized = false;
    loop {
        let available = reader.fill_buf()?;
        if available.is_empty() {
            return if out.is_empty() && !oversized {
                Ok(None)
            } else {
                Ok(Some(if oversized { Err(()) } else { Ok(out) }))
            };
        }
        let end = available.iter().position(|&b| b == b'\n');
        let take = end.map(|n| n + 1).unwrap_or(available.len());
        if !oversized {
            if out.len() + take > maximum + 1 {
                oversized = true;
                out.clear()
            } else {
                out.extend_from_slice(&available[..take]);
            }
        }
        reader.consume(take);
        if end.is_some() {
            if out.last() == Some(&b'\n') {
                out.pop();
                if out.last() == Some(&b'\r') {
                    out.pop();
                }
            }
            return Ok(Some(if oversized { Err(()) } else { Ok(out) }));
        }
    }
}

fn failure(command_id: &str, code: &str) -> CommandResult {
    CommandResult {
        version: CONTRACT_VERSION,
        command_id: command_id.into(),
        generation: 0,
        ok: false,
        code: code.into(),
        payload: Value::Null,
    }
}

fn main() {
    let nonce = std::env::var("SHACKCQ_RUNTIME_NONCE").unwrap_or_default();
    let key_hex = std::env::var("SHACKCQ_QUEUE_KEY_HEX").unwrap_or_default();
    let queue_path = std::env::var("SHACKCQ_QUEUE_PATH").unwrap_or_default();
    let key: [u8; 32] = match hex::decode(key_hex).ok().and_then(|v| v.try_into().ok()) {
        Some(key) if nonce.len() >= 32 && !queue_path.is_empty() => key,
        _ => return,
    };
    let mut runtime = match StationRuntime::new(queue_path, QueueKey::from_bytes(key)) {
        Ok(runtime) => runtime,
        Err(_) => return,
    };
    let stdin = io::stdin();
    let mut stdout = io::stdout().lock();
    let mut input = stdin.lock();
    while let Ok(Some(line)) = read_bounded_line(&mut input, MAX_COMMAND_BYTES) {
        let result = if line.is_err() {
            failure("", "IPC_COMMAND_TOO_LARGE")
        } else {
            match serde_json::from_slice::<CommandEnvelope>(&line.unwrap()) {
                Ok(envelope) => runtime.process(envelope, &nonce),
                Err(_) => failure("", "IPC_JSON_REJECTED"),
            }
        };
        if serde_json::to_writer(&mut stdout, &result).is_err()
            || stdout.write_all(b"\n").is_err()
            || stdout.flush().is_err()
        {
            break;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    #[test]
    fn oversized_line_is_drained_and_next_frame_recovers() {
        let mut data = vec![b'x'; 32];
        data.extend_from_slice(b"\n{}\n");
        let mut cursor = Cursor::new(data);
        assert_eq!(read_bounded_line(&mut cursor, 8).unwrap(), Some(Err(())));
        assert_eq!(
            read_bounded_line(&mut cursor, 8).unwrap(),
            Some(Ok(b"{}".to_vec()))
        );
    }
}
