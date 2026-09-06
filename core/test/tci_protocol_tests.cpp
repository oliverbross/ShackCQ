// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/tci.hpp"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

int main() {
    using namespace shackcq::tci;

    const auto commands = parse_status(
        " protocol:ExpertSDR3,1.8;trx_count:2;VFO:0,0,14074000; ready; bad-name:x;");
    assert(commands.size() == 4U);
    assert(commands[0].name == "protocol" && commands[0].arguments == "ExpertSDR3,1.8");
    assert(commands[1].name == "trx_count" && commands[1].arguments == "2");
    assert(commands[2].name == "vfo" && commands[2].arguments == "0,0,14074000");
    assert(commands[3].name == "ready" && commands[3].arguments.empty());

    assert(canonical_mode("USB") == "usb");
    assert(canonical_mode("FT8") == "digu");
    assert(!canonical_mode("invented"));
    assert(build_vfo(1U, 0U, 14'074'000U) == "vfo:1,0,14074000;");
    assert(build_if(1U, 0U, -1'500) == "if:1,0,-1500;");
    assert(build_mode(1U, "FT4") == "modulation:1,digu;");
    assert(build_volume(-20) == "volume:-20;");
    assert(build_volume(-99) == "volume:-60;");
    assert(build_split_enable(1U, true) == "split_enable:1,true;");
    assert(!build_split_enable(9U, true));
    assert(build_iq_sample_rate(96'000U) == "iq_samplerate:96000;");
    assert(build_iq_start(1U) == "iq_start:1;");
    assert(build_iq_stop(1U) == "iq_stop:1;");
    assert(build_audio_start(1U) == "audio_start:1;");
    assert(build_audio_stop(1U) == "audio_stop:1;");
    assert(build_rx_enable(1U, true) == "rx_enable:1,true;");
    assert(build_mute(1U, false) == "mute:1,false;");
    assert(build_safe_stop(1U) == "trx:1,false;tune:1,false;");
    assert(build_trx(1U, true) == "trx:1,true,tci;");
    assert(build_trx(1U, false) == "trx:1,false;");
    assert(build_tune(1U, true) == "tune:1,true;");
    assert(build_drive(1U, 35U) == "drive:1,35;");
    assert(!build_drive(1U, 101U));
    assert(build_tune_drive(1U, 10U) == "tune_drive:1,10;");
    assert(build_audio_sample_rate(48'000U) == "audio_samplerate:48000;");
    assert(!build_audio_sample_rate(44'100U));
    assert(build_tx_sensors_enable(1U, true) == "tx_sensors_enable:1,true;");
    assert(build_xit_enable(1U, true) == "xit_enable:1,true;");
    assert(build_xit_offset(1U, -250) == "xit_offset:1,-250;");
    assert(build_monitor_enable(1U, true) == "mon_enable:1,true;");
    assert(!build_vfo(9U, 0U, 14'074'000U));
    assert(!build_vfo(0U, 2U, 14'074'000U));
    assert(!build_iq_sample_rate(1U));

    const std::vector<float> iq{0.25F, -0.5F, 0.75F, -1.0F};
    const auto bytes = build_binary_for_test(DataType::Iq, 1U, 96'000U, 2U, iq);
    assert(bytes.size() == BinaryHeaderBytes + iq.size() * sizeof(float));
    BinaryError error{};
    const auto frame = decode_binary(bytes.data(), bytes.size(), &error, 2U);
    assert(frame && error == BinaryError::None);
    assert(frame->header.receiver == 1U && frame->header.sample_rate == 96'000U);
    assert(frame->header.format == Float32Format && frame->header.channels == 2U);
    assert(frame->header.data_type == DataType::Iq && frame->values == iq);

    for (const auto type : {DataType::RxAudio, DataType::TxAudio}) {
        const auto audio = build_binary_for_test(type, 0U, 48'000U, 2U, iq);
        const auto audio_frame = decode_binary(audio.data(), audio.size(), &error, 1U);
        assert(audio_frame && audio_frame->header.data_type == type && audio_frame->values == iq);
    }

    auto malformed = bytes;
    malformed.resize(BinaryHeaderBytes + sizeof(float));
    assert(!decode_binary(malformed.data(), malformed.size(), &error, 2U));
    assert(error == BinaryError::PayloadLengthMismatch);
    assert(!decode_binary(bytes.data(), BinaryHeaderBytes - 1U, &error, 2U));
    assert(error == BinaryError::HeaderTooShort);
    assert(!decode_binary(bytes.data(), bytes.size(), &error, 1U));
    assert(error == BinaryError::ReceiverOutOfRange);
    assert(!decode_binary(bytes.data(), bytes.size(), &error, 2U, BinaryHeaderBytes));
    assert(error == BinaryError::MessageTooLarge);

    malformed = bytes;
    malformed[8] = 1U;
    assert(!decode_binary(malformed.data(), malformed.size(), &error, 2U));
    assert(error == BinaryError::UnsupportedFormat);
    malformed = bytes;
    malformed[28] = 1U;
    assert(!decode_binary(malformed.data(), malformed.size(), &error, 2U));
    assert(error == BinaryError::InvalidChannels);
    malformed = bytes;
    malformed[24] = 99U;
    assert(!decode_binary(malformed.data(), malformed.size(), &error, 2U));
    assert(error == BinaryError::UnknownDataType);

    const std::vector<float> non_finite{0.0F, std::numeric_limits<float>::quiet_NaN()};
    malformed = build_binary_for_test(DataType::Iq, 0U, 48'000U, 2U, non_finite);
    assert(!decode_binary(malformed.data(), malformed.size(), &error, 1U));
    assert(error == BinaryError::NonFiniteSample);

    const auto chrono = build_binary_for_test(DataType::TxChrono, 0U, 48'000U, 2U, {}, 960U);
    const auto chrono_frame = decode_binary(chrono.data(), chrono.size(), &error, 1U);
    assert(chrono_frame && chrono_frame->header.data_type == DataType::TxChrono);
    assert(chrono_frame->header.value_count == 960U && chrono_frame->values.empty());

    const std::vector<float> mono{0.0F, 0.5F, 1.0F, -1.0F};
    const auto tx = build_tx_audio(0U, 24'000U, 48'000U, mono.data(), mono.size(), 0U, 8U, 2.0F);
    assert(tx && tx->size() == BinaryHeaderBytes + 8U * sizeof(float));
    const auto tx_frame = decode_binary(tx->data(), tx->size(), &error, 1U);
    assert(tx_frame && tx_frame->header.data_type == DataType::TxAudio);
    assert(tx_frame->header.sample_rate == 48'000U && tx_frame->header.channels == 2U);
    assert(tx_frame->values.size() == 8U && tx_frame->values[4] == 0.98F);
    assert(!build_tx_audio(0U, 24'000U, 48'000U, non_finite.data(), non_finite.size(), 0U, 8U, 1.0F));
    assert(!build_tx_audio(0U, 24'000U, 48'000U, mono.data(), mono.size(), 0U, 7U, 1.0F));

    assert(std::string(*build_safe_stop(0U)).find("true") == std::string::npos);
}
