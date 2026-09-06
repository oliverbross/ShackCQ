// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 KD9TAW <kd9taw@protonmail.com>
// Copyright (C) 2026 Oliver Bross
//
// Flex protocol portions are derived from Nexus commit
// 6ec4a7925f1550cc364c7fd95967ce38c696ad3f. See ../UPSTREAM.md.

mod ffi;
pub mod digi;
pub mod flexcat;
pub mod flexdisc;
pub mod vita;

pub use flexcat::{
    client_identity_command, encode_command, filter_command, frequency_command, keepalive_command,
    mode_command, subscriptions, FlexFramer, FlexMessage, FlexState,
};
pub use flexdisc::{parse_discovery, DiscoveryRecord};
