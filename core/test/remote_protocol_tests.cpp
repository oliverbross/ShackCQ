// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/remote.h"

#include <cassert>
#include <iostream>

using namespace shackcq::remote;

int main() {
  SessionAuthority authority;
  const PairingOffer offer{"station-1", "wss://station.local:7443",
      std::string(64, 'a'), "0123456789abcdef", Role::Observer, 10'000};
  assert(authority.registerPairingOffer(offer, 1'000));
  assert(authority.consumePairingOffer(offer.nonce, "operator-tablet",
      std::string(96, 'k'), Role::Operator, 2'000));
  assert(!authority.consumePairingOffer(offer.nonce, "replay", std::string(96, 'k'), Role::Admin, 2'001));
  const auto session = authority.openSession("operator-tablet", true, 7, 3'000);
  assert(session);
  assert(authority.acquire(*session, Lease::Writer, 3'000, 5'000));
  assert(!authority.acquire(*session, Lease::Transmit, 3'001, 5'000));
  assert(authority.acquire(*session, Lease::Transmit, 3'001, 5'000, true));
  authority.heartbeat(*session, false, 7, 3'500);
  assert(!authority.sessions().front().transmit);
  assert(authority.acquire(*session, Lease::Rotator, 3'600, 5'000, false, true));
  authority.localPreempt();
  assert(!authority.sessions().front().writer && !authority.sessions().front().rotator);
  authority.revoke("operator-tablet");
  assert(authority.sessions().empty() && !authority.paired("operator-tablet"));

  SessionAuthority restored;
  assert(restored.restorePairedDevice("restored-device", "restored-public-key", Role::Admin));
  assert(restored.paired("restored-device"));
  assert(restored.openSession("restored-device", true, 8, 4'000));
  assert(restored.restorePairedDevice("revoked-device", "revoked-public-key", Role::Operator, true));
  assert(!restored.openSession("revoked-device", true, 8, 4'000));

  MediaFrame media{Channel::Spectrum, 3, 42, 1000, 7, {1, 2, 3, 4}};
  const auto encoded = encodeMedia(media);
  const auto decoded = decodeMedia(encoded.data(), encoded.size());
  assert(decoded && decoded->channel == Channel::Spectrum && decoded->sequence == 42 && decoded->payload == media.payload);
  auto malformed = encoded; malformed[32] = 0xff;
  assert(!decodeMedia(malformed.data(), malformed.size()));

  const RigState rig{14'074'000, "USB", 2400, "VFOA", false, 14'076'000, "USB", 0, 0, false};
  assert(handleRigctld("f\n", rig, false, false).response == "14074000\n");
  assert(handleRigctld("F 14075000\r\n", rig, false, false).errorCode == -8);
  assert(handleRigctld("F 14075000\n", rig, true, false).accepted);
  assert(handleRigctld("T 1\n", rig, true, false).errorCode == -8);
  assert(handleRigctld("T 1\n", rig, true, true).accepted);
  assert(handleRigctld("\\send_cmd FA;\n", rig, true, true).errorCode == -4);
  assert(handleTci("vfo;", rig, false, false).accepted);
  assert(!handleTci("vfo:0,0,14075000;", rig, false, false).accepted);
  assert(handleTci("vfo:0,0,14075000;", rig, true, false).accepted);
  assert(!handleTci("trx:0,true,tci;", rig, true, false).accepted);
  assert(handleTci("trx:0,true,tci;", rig, true, true).accepted);
  std::cout << "remote protocol/session/media/bridge tests passed\n";
}
