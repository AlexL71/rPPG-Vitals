import numpy as np
import pytest

from rppg import dsp
from rppg.synthetic import PBV

FS = 30.0


def test_resample_uniform_linear():
    t = np.array([0.0, 0.03, 0.07, 0.1])
    x = 2.0 * t + 1.0
    tu, xu = dsp.resample_uniform(t, x, FS)
    assert tu[0] == 0.0 and np.allclose(np.diff(tu), 1 / FS)
    assert np.allclose(xu, 2.0 * tu + 1.0)


def test_pos_recovers_pulse_frequency_under_lighting_change():
    t = np.arange(0, 10, 1 / FS)
    pulse = np.sin(2 * np.pi * 1.2 * t)                       # 72 BPM
    light = 1.0 + 0.05 * np.sin(2 * np.pi * 0.9 * t)          # strong in-band lighting flicker
    rgb = np.array([170, 120, 100]) * light[:, None] * (1 + 0.004 * pulse[:, None] * PBV)
    h = dsp.pos(rgb, FS)
    f, p = dsp.power_spectrum(h, FS, 4096)
    assert dsp.find_peak_hz(f, p, (0.7, 3.0)) == pytest.approx(1.2, abs=0.02)


def test_green_alone_is_fooled_by_lighting_but_pos_is_not():
    # documents WHY we use POS: lighting at 0.9 Hz is stronger than the pulse
    t = np.arange(0, 10, 1 / FS)
    pulse = np.sin(2 * np.pi * 1.5 * t)
    light = 1.0 + 0.05 * np.sin(2 * np.pi * 0.9 * t)
    rgb = np.array([170, 120, 100]) * light[:, None] * (1 + 0.004 * pulse[:, None] * PBV)
    f, p = dsp.power_spectrum(rgb[:, 1], FS, 4096)
    assert dsp.find_peak_hz(f, p, (0.7, 3.0)) == pytest.approx(0.9, abs=0.02)
    f, p = dsp.power_spectrum(dsp.pos(rgb, FS), FS, 4096)
    assert dsp.find_peak_hz(f, p, (0.7, 3.0)) == pytest.approx(1.5, abs=0.02)


def test_parabolic_peak_is_more_precise_than_bin():
    t = np.arange(0, 10, 1 / FS)
    x = np.sin(2 * np.pi * 1.234 * t)
    f, p = dsp.power_spectrum(x, FS, 256)          # coarse bins (0.117 Hz)
    assert abs(dsp.find_peak_hz(f, p, (0.7, 3.0)) - 1.234) < 0.03


def test_peak_ignores_drift_at_band_edge():
    # regression: slow drift made the 0.7 Hz edge bin win (showed 42-44 BPM at start-up)
    f = np.linspace(0, 15, 2049)
    p = 100.0 / (1.0 + (f / 0.2) ** 2) + 2.0 * np.exp(-0.5 * ((f - 1.2) / 0.05) ** 2)
    assert p[np.searchsorted(f, 0.7)] > p[np.searchsorted(f, 1.2)]   # edge is higher than the pulse
    assert dsp.find_peak_hz(f, p, (0.7, 3.0)) == pytest.approx(1.2, abs=0.01)


def test_snr_high_for_sine_low_for_noise():
    rng = np.random.default_rng(0)
    t = np.arange(0, 10, 1 / FS)
    hw = 2 * FS / t.size
    f, p = dsp.power_spectrum(np.sin(2 * np.pi * 1.2 * t) + 0.1 * rng.normal(size=t.size), FS, 4096)
    assert dsp.snr_db(f, p, 1.2, hw, (0.7, 4.0)) > 10
    f, p = dsp.power_spectrum(rng.normal(size=t.size), FS, 4096)
    f0 = dsp.find_peak_hz(f, p, (0.7, 3.0))
    assert dsp.snr_db(f, p, f0, hw, (0.7, 4.0)) < 0


def test_bandpass_keeps_inband_removes_outband():
    t = np.arange(0, 20, 1 / FS)
    inband = np.sin(2 * np.pi * 1.2 * t)
    y = dsp.bandpass_fft(inband + 2 * np.sin(2 * np.pi * 0.2 * t) + np.sin(2 * np.pi * 6 * t), FS, (0.7, 3.0))
    mid = slice(60, -60)  # ignore edges
    assert np.corrcoef(y[mid], inband[mid])[0, 1] > 0.99


def test_detect_beats_and_hrv_on_known_rhythm():
    ibi = np.tile([0.80, 0.84, 0.78, 0.86], 10)            # known intervals (s)
    beats_true = np.concatenate([[0.5], 0.5 + np.cumsum(ibi)])
    t = np.arange(0, beats_true[-1] + 0.5, 1 / FS)
    y = np.zeros_like(t)
    for b in beats_true:                                   # narrow Gaussian pulse per beat
        y += np.exp(-0.5 * ((t - b) / 0.08) ** 2)
    y = dsp.bandpass_fft(y, FS, (0.7, 3.0))
    beats = dsp.detect_beats(y, FS, 1 / ibi.mean())
    assert beats.size == beats_true.size
    assert np.max(np.abs(beats - beats_true)) < 0.02
    res = dsp.hrv_from_beats(beats, min_beats=15)
    true_rmssd = np.sqrt(np.mean(np.diff(ibi) ** 2)) * 1000
    assert res["rmssd_ms"] == pytest.approx(true_rmssd, rel=0.2)
