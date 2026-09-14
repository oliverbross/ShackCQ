// SPDX-License-Identifier: GPL-3.0-only
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use chacha20poly1305::{
    aead::{Aead, KeyInit},
    XChaCha20Poly1305, XNonce,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    fs,
    path::{Path, PathBuf},
};
use thiserror::Error;

const MAX_CONTACTS: usize = 5_000;
const MAX_PAYLOAD_BYTES: usize = 32 * 1024 * 1024;

#[derive(Clone)]
pub struct QueueKey([u8; 32]);

impl QueueKey {
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }
    pub fn generate() -> Result<Self, QueueError> {
        let mut bytes = [0u8; 32];
        getrandom::fill(&mut bytes).map_err(|_| QueueError::KeyUnavailable)?;
        Ok(Self(bytes))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ReviewedContact {
    pub event_id: String,
    #[serde(default)]
    pub operation_identity: String,
    #[serde(default)]
    pub profile_id: String,
    #[serde(default)]
    pub source_revision: u32,
    pub account_id: String,
    pub station_profile_id: String,
    pub destination_authority: String,
    pub authority_revision: u32,
    pub mapping_revision: Option<u32>,
    pub captured_utc: String,
    pub provenance: String,
    pub fixture: bool,
    pub contact: Value,
}

impl ReviewedContact {
    fn validate(&self) -> Result<(), QueueError> {
        if self.event_id.is_empty()
            || self.event_id.len() > 160
            || self.account_id.is_empty()
            || self.account_id.len() > 160
            || self.station_profile_id.is_empty()
            || self.station_profile_id.len() > 160
            || !matches!(self.destination_authority.as_str(), "WEB_LOCAL" | "WAVELOG")
            || self.authority_revision == 0
            || self.operation_identity.is_empty()
            || self.profile_id.is_empty()
            || self.source_revision == 0
            || self.provenance != "NEXUS_NATIVE"
        {
            return Err(QueueError::InvalidContact);
        }
        if serde_json::to_vec(&self.contact)
            .map_err(|_| QueueError::InvalidContact)?
            .len()
            > 16 * 1024
        {
            return Err(QueueError::InvalidContact);
        }
        Ok(())
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct CipherFile {
    version: u16,
    nonce: String,
    ciphertext: String,
}

#[derive(Debug, Error)]
pub enum QueueError {
    #[error("queue encryption key unavailable")]
    KeyUnavailable,
    #[error("invalid reviewed contact")]
    InvalidContact,
    #[error("queue full")]
    Full,
    #[error("durable canonical receipt required")]
    ReceiptRequired,
    #[error("queue data is invalid or cannot be decrypted")]
    Corrupt,
    #[error("queue I/O failed")]
    Io,
}

pub struct ContactQueue {
    path: PathBuf,
    key: QueueKey,
    rows: Vec<ReviewedContact>,
}

impl ContactQueue {
    pub fn open(path: impl AsRef<Path>, key: QueueKey) -> Result<Self, QueueError> {
        let path = path.as_ref().to_path_buf();
        let rows = if path.exists() {
            let wrapped: CipherFile =
                serde_json::from_slice(&fs::read(&path).map_err(|_| QueueError::Io)?)
                    .map_err(|_| QueueError::Corrupt)?;
            if wrapped.version != 1 {
                return Err(QueueError::Corrupt);
            }
            let nonce = B64.decode(wrapped.nonce).map_err(|_| QueueError::Corrupt)?;
            let ciphertext = B64
                .decode(wrapped.ciphertext)
                .map_err(|_| QueueError::Corrupt)?;
            let cipher = XChaCha20Poly1305::new((&key.0).into());
            let plaintext = cipher
                .decrypt(XNonce::from_slice(&nonce), ciphertext.as_ref())
                .map_err(|_| QueueError::Corrupt)?;
            serde_json::from_slice(&plaintext).map_err(|_| QueueError::Corrupt)?
        } else {
            Vec::new()
        };
        Ok(Self { path, key, rows })
    }

    pub fn len(&self) -> usize {
        self.rows.len()
    }
    pub fn pending(&self) -> &[ReviewedContact] {
        &self.rows
    }

    pub fn enqueue(&mut self, mut contact: ReviewedContact) -> Result<String, QueueError> {
        contact.validate()?;
        let canonical =
            serde_json::to_vec(&contact.contact).map_err(|_| QueueError::InvalidContact)?;
        let derived = format!(
            "nx-{}",
            hex::encode(Sha256::digest(
                [
                    contact.account_id.as_bytes(),
                    contact.station_profile_id.as_bytes(),
                    contact.operation_identity.as_bytes(),
                    contact.profile_id.as_bytes(),
                    &contact.source_revision.to_be_bytes(),
                    contact.destination_authority.as_bytes(),
                    &contact.authority_revision.to_be_bytes(),
                    &contact.mapping_revision.unwrap_or(0).to_be_bytes(),
                    contact.captured_utc.as_bytes(),
                    &canonical
                ]
                .concat()
            ))
        );
        contact.event_id = derived.clone();
        if self.rows.iter().any(|r| r.event_id == derived) {
            return Ok(derived);
        }
        if self.rows.len() >= MAX_CONTACTS {
            return Err(QueueError::Full);
        }
        let current = serde_json::to_vec(&self.rows)
            .map_err(|_| QueueError::Corrupt)?
            .len();
        if current + canonical.len() > MAX_PAYLOAD_BYTES {
            return Err(QueueError::Full);
        }
        self.rows.push(contact);
        if let Err(error) = self.save() {
            self.rows.pop();
            return Err(error);
        }
        Ok(derived)
    }

    pub fn acknowledge(&mut self, event_id: &str, durable_receipt: bool) -> Result<(), QueueError> {
        if !durable_receipt {
            return Err(QueueError::ReceiptRequired);
        }
        let before = self.rows.clone();
        self.rows.retain(|r| r.event_id != event_id);
        if let Err(error) = self.save() {
            self.rows = before;
            return Err(error);
        }
        Ok(())
    }

    fn save(&self) -> Result<(), QueueError> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).map_err(|_| QueueError::Io)?;
        }
        let plaintext = serde_json::to_vec(&self.rows).map_err(|_| QueueError::Corrupt)?;
        let mut nonce = [0u8; 24];
        getrandom::fill(&mut nonce).map_err(|_| QueueError::KeyUnavailable)?;
        let cipher = XChaCha20Poly1305::new((&self.key.0).into());
        let ciphertext = cipher
            .encrypt(XNonce::from_slice(&nonce), plaintext.as_ref())
            .map_err(|_| QueueError::Corrupt)?;
        let wrapped = CipherFile {
            version: 1,
            nonce: B64.encode(nonce),
            ciphertext: B64.encode(ciphertext),
        };
        let temp = self.path.with_extension("tmp");
        fs::write(
            &temp,
            serde_json::to_vec(&wrapped).map_err(|_| QueueError::Corrupt)?,
        )
        .map_err(|_| QueueError::Io)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&temp, fs::Permissions::from_mode(0o600))
                .map_err(|_| QueueError::Io)?;
        }
        fs::rename(temp, &self.path).map_err(|_| QueueError::Io)
    }
}
