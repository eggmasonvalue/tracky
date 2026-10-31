"""Regression tests for stable partial-zero and standalone word blocks."""

import pytest

from tracky_mcp.protocol import (
    AcquisitionCompletionPolicy,
    MeasurementQuality,
    append_checksum,
    decode_frame,
)


def _measurement(marker: tuple[int, int], words: tuple[int, ...]) -> bytes:
    body = bytes((0x10, 0x12, 0x20, 0x13, 0x88, *marker))
    return append_checksum(body + b"".join(word.to_bytes(2, "big") for word in words))


def _observe(policy: AcquisitionCompletionPolicy, raw: bytes) -> None:
    policy.observe_wire("rx", raw)
    policy.observe(decode_frame(raw))
    if raw[5:7] == b"\x01\x21":
        policy.observe_wire("tx", bytes.fromhex("1f0620102176"))


@pytest.mark.parametrize("words", [(0, 2, 3, 4, 5), (0, 0, 0, 0, 0)])
def test_stable_partially_zero_burst_is_not_accepted(words: tuple[int, ...]) -> None:
    policy = AcquisitionCompletionPolicy(stable_measurements=3)
    for raw in (
        *[_measurement((1, 0x21), words)] * 3,
        *[_measurement((1, 0x22), (6, 7, 8, 9, 10))] * 3,
    ):
        _observe(policy, raw)
    decision = policy.finish("stream_closed")
    assert decision.quality is MeasurementQuality.INDETERMINATE
    assert decision.reason == "stable_complete_partial_zero_word_burst"
    assert decision.selected_frame_raw is not None  # Weight is independent.


@pytest.mark.parametrize("zero", [True, False])
def test_stable_standalone_block_uses_run_count_before_commit(zero: bool) -> None:
    policy = AcquisitionCompletionPolicy(stable_measurements=3)
    raw = _measurement((1, 0x21), (0, 0, 0, 0, 0) if zero else (1, 2, 3, 4, 5))
    for _ in range(3):
        _observe(policy, raw)
    decision = policy.finish("stream_closed")
    assert decision.quality is (
        MeasurementQuality.CONTACT_FAILED if zero else MeasurementQuality.INDETERMINATE
    )
    assert decision.reason == (
        "stable_zero_word_block" if zero
        else "stable_measurement_without_accepted_word_burst"
    )
    assert policy.finish("explicit_stop") is decision


def test_interrupted_standalone_run_does_not_claim_stability() -> None:
    policy = AcquisitionCompletionPolicy(stable_measurements=3)
    for _ in range(3):
        _observe(policy, _measurement((1, 0x21), (0, 0, 0, 0, 0)))
    policy.observe(decode_frame(append_checksum(bytes((0x12, 0x04, 0x20, 0)))))
    assert policy.finish("stream_closed").reason == "stream_closed"
