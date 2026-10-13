import numpy as np
import pytest

from rppg import RppgProcessor
from rppg.synthetic import SyntheticSpec, make_trace


def run(trace, step=1.0):
    proc = RppgProcessor()
    ests, next_t = [], step
    for i in range(trace["t"].size):
        t = trace["t"][i]
        proc.add(t, (trace["r"][i], trace["g"][i], trace["b"][i]),
                 trace["cx"][i], trace["cy"][i], trace["size"][i])
        if t >= next_t:
            ests.append(proc.estimate(now=t))
            next_t += step
    return ests


def test_synthetic_trace_gives_correct_vitals():
    spec = SyntheticSpec(hr_bpm=72, rr_bpm=15)
    ests = run(make_trace(spec))
    assert ests[0].quality == "warming_up"
    last = ests[-1]
    assert last.quality == "good"
    assert last.hr_bpm == pytest.approx(72, abs=1.5)
    assert last.rr_bpm == pytest.approx(15, abs=1.5)
    assert last.rmssd_ms is not None and last.n_beats >= 30
    assert last.waveform.size == 300


@pytest.mark.parametrize("hr", [50, 95, 140])
def test_heart_rate_range(hr):
    ests = run(make_trace(SyntheticSpec(hr_bpm=hr, duration_sec=20)))
    assert ests[-1].hr_bpm == pytest.approx(hr, abs=2)


def test_noise_only_is_poor_and_hidden():
    tr = make_trace(SyntheticSpec(pulse_amp=0.0, duration_sec=20))
    last = run(tr)[-1]
    assert last.quality in ("poor", "fair")
    poor = [e for e in run(tr) if e.quality == "poor"]
    assert poor and all(e.hr_bpm is None for e in poor)


def test_face_lost_resets_buffer():
    tr = make_trace(SyntheticSpec(duration_sec=20))
    proc = RppgProcessor()
    for i in range(tr["t"].size):
        t = tr["t"][i]
        if 8.0 < t < 9.0:  # face lost for 1 s
            continue
        proc.add(t, (tr["r"][i], tr["g"][i], tr["b"][i]), tr["cx"][i], tr["cy"][i], tr["size"][i])
    assert proc.data_sec < 11.5   # only data after the gap is kept
    assert proc.estimate(now=tr["t"][-1] + 2.0).quality == "no_face"


def test_head_motion_flagged():
    tr = make_trace(SyntheticSpec(duration_sec=15))
    tr["cx"] = tr["cx"] + 30 * np.sin(2 * np.pi * 1.0 * tr["t"])   # strong shaking
    assert run(tr)[-1].quality == "moving"
