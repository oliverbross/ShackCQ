// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/remote.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <iostream>
#include <string>
#include <vector>

using namespace shackcq::remote;

int main() {
  SessionAuthority authority;
  std::vector<std::string> sessions;
  for (std::uint64_t index = 0; index < MaxSessions; ++index) {
    const std::string suffix = std::to_string(index);
    const PairingOffer offer{"station-scale", "wss://127.0.0.1:7443", std::string(64, 'a'),
        "nonce-scale-000000000000" + suffix, Role::Observer, 60'000};
    assert(authority.registerPairingOffer(offer, 1'000));
    const Role role = index == 0 ? Role::Operator : Role::Observer;
    assert(authority.consumePairingOffer(offer.nonce, "device-scale-" + suffix,
        std::string(96, static_cast<char>('a' + index)), role, 1'001));
    const auto session = authority.openSession("device-scale-" + suffix, true, 9, 2'000);
    assert(session); sessions.push_back(*session);
  }
  assert(authority.sessions().size() == MaxSessions);
  assert(!authority.openSession("device-scale-0", true, 9, 2'001));
  assert(authority.acquire(sessions.front(), Lease::Writer, 3'000, 5'000));
  for (std::size_t index = 1; index < sessions.size(); ++index)
    assert(!authority.acquire(sessions[index], Lease::Writer, 3'001, 5'000));
  authority.globalStop();
  for (const auto &row : authority.sessions()) assert(!row.writer && !row.transmit && !row.rotator);

  std::array<std::uint8_t, 2048> spectrum{};
  for (std::size_t index = 0; index < spectrum.size(); ++index)
    spectrum[index] = static_cast<std::uint8_t>(index & 0xff);
  for (std::uint32_t index = 0; index < 10'000; ++index) {
    MediaFrame frame{Channel::Spectrum, 0, index, index * 20ULL, 9,
        std::vector<std::uint8_t>(spectrum.begin(), spectrum.end())};
    const auto encoded = encodeMedia(frame);
    const auto decoded = decodeMedia(encoded.data(), encoded.size());
    assert(decoded && decoded->sequence == index && decoded->payload.size() == spectrum.size());
    auto malformed = encoded;
    malformed[index % 4] ^= 0xff;
    assert(!decodeMedia(malformed.data(), malformed.size()));
  }

  std::uint64_t spectrumBytes{};
  std::uint64_t audioBytes{};
  std::uint64_t digiBytes{};
  const std::vector<std::uint8_t> reducedSpectrum(256, 127);
  const std::vector<std::uint8_t> pcm20ms(644, 0);
  for (std::uint32_t index = 0; index < 36'000; ++index) {
    const MediaFrame frame{Channel::Waterfall, 0, index, index * 50ULL, 9, reducedSpectrum};
    const auto bytes = encodeMedia(frame); spectrumBytes += bytes.size();
    assert(decodeMedia(bytes.data(), bytes.size()));
  }
  for (std::uint32_t index = 0; index < 90'000; ++index) {
    const MediaFrame frame{Channel::AudioRx, 0, index, index * 20ULL, 9, pcm20ms};
    const auto bytes = encodeMedia(frame); audioBytes += bytes.size();
    assert(decodeMedia(bytes.data(), bytes.size()));
  }
  for (std::uint32_t index = 0; index < 1'800; ++index) {
    const MediaFrame frame{Channel::Digi, 0, index, index * 1'000ULL, 9, {0, 1, 2, 3}};
    const auto bytes = encodeMedia(frame); digiBytes += bytes.size();
    assert(decodeMedia(bytes.data(), bytes.size()));
  }

  SessionAuthority leaseCycles;
  const PairingOffer cycleOffer{"station-cycle", "wss://127.0.0.1:7443", std::string(64, 'b'),
      "cycle-nonce-000000000000", Role::Operator, 60'000};
  assert(leaseCycles.registerPairingOffer(cycleOffer, 0));
  assert(leaseCycles.consumePairingOffer(cycleOffer.nonce, "cycle-device", std::string(96, 'c'),
      Role::Operator, 1));
  for (std::uint64_t index = 0; index < 1'000; ++index) {
    const auto session = leaseCycles.openSession("cycle-device", true, index + 1, index * 100 + 10);
    assert(session);
    assert(leaseCycles.acquire(*session, Lease::Writer, index * 100 + 11, 1'000));
    assert(leaseCycles.acquire(*session, Lease::Transmit, index * 100 + 12, 1'000, true));
    assert(leaseCycles.acquire(*session, Lease::Rotator, index * 100 + 13, 1'000, false, true));
    leaseCycles.globalStop();
    leaseCycles.closeSession(*session);
  }
  assert(leaseCycles.sessions().empty());
  std::cout << "remote scale: 8 sessions, 10000 media/malformed frames, 1000 reconnect/lease/rotator cycles, "
            << "1800 simulated seconds waterfall=" << spectrumBytes
            << "B audio=" << audioBytes << "B digi=" << digiBytes << "B, drops=0 passed\n";
}
