"""Write golden files for the Kotlin unit test (Python implementation = reference).

    python python/tools/make_golden.py

Creates android/app/src/test/resources/golden_trace.csv and golden_expected.json.
The Kotlin GoldenTest replays the CSV through RppgProcessor.kt and must match the
Python numbers below within small tolerances.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rppg import RppgProcessor  # noqa: E402
from rppg.config import REPO_ROOT  # noqa: E402
from rppg.session_io import SessionWriter  # noqa: E402
from rppg.synthetic import SyntheticSpec, make_trace  # noqa: E402

OUT_DIR = REPO_ROOT / "android" / "app" / "src" / "test" / "resources"
CHECK_TIMES = (5.0, 8.0, 12.0, 20.0, 31.0, 39.0)   # estimate after all samples with t <= T


def main() -> None:
    spec = SyntheticSpec(duration_sec=40.0, hr_bpm=72.0, rr_bpm=15.0)
    tr = make_trace(spec)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    w = SessionWriter(OUT_DIR / "golden_trace.csv", {"source": "synthetic", "hr_bpm": "72", "rr_bpm": "15"})
    for i in range(tr["t"].size):
        w.write(tr["t"][i], (tr["r"][i], tr["g"][i], tr["b"][i]), tr["cx"][i], tr["cy"][i], tr["size"][i])
    w.close()

    # re-read the CSV so Python and Kotlin start from exactly the same rounded numbers
    from rppg.session_io import read_session
    data, _ = read_session(OUT_DIR / "golden_trace.csv")
    proc = RppgProcessor()
    expected, k = [], 0
    t = data["t_sec"]
    for i in range(t.size):
        while k < len(CHECK_TIMES) and t[i] > CHECK_TIMES[k]:
            expected.append(snapshot(proc.estimate(), CHECK_TIMES[k]))
            k += 1
        proc.add(t[i], (data["r"][i], data["g"][i], data["b"][i]), data["cx"][i], data["cy"][i], data["size"][i])
    while k < len(CHECK_TIMES):
        expected.append(snapshot(proc.estimate(), CHECK_TIMES[k]))
        k += 1

    (OUT_DIR / "golden_expected.json").write_text(json.dumps(expected, indent=2))
    # flat CSV for the Kotlin test (no JSON library needed); "nan" = value absent
    cols = ["check_t", "quality", "data_sec", "hr_bpm", "hr_raw_bpm", "snr_db", "motion", "rr_bpm",
            "rr_snr_db", "rr_source", "rmssd_ms", "sdnn_ms", "n_beats", "waveform_len"]
    with open(OUT_DIR / "golden_expected.csv", "w", encoding="utf-8", newline="\n") as f:
        f.write(",".join(cols + [f"w{i}" for i in range(5)]) + "\n")
        for e in expected:
            vals = ["nan" if e[c] is None else str(e[c]) for c in cols]
            head = [repr(v) for v in e["waveform_head"]] + ["nan"] * (5 - len(e["waveform_head"]))
            f.write(",".join(vals + head) + "\n")
    for e in expected:
        print(e)
    print(f"written to {OUT_DIR}")


def snapshot(e, check_t: float) -> dict:
    return {
        "check_t": check_t, "quality": e.quality, "data_sec": round(e.data_sec, 6),
        "hr_bpm": e.hr_bpm, "hr_raw_bpm": e.hr_raw_bpm, "snr_db": e.snr_db, "motion": e.motion,
        "rr_bpm": e.rr_bpm, "rr_snr_db": e.rr_snr_db, "rr_source": e.rr_source,
        "rmssd_ms": e.rmssd_ms, "sdnn_ms": e.sdnn_ms, "n_beats": e.n_beats,
        "waveform_len": int(e.waveform.size),
        "waveform_head": [float(v) for v in e.waveform[:5]],
    }


if __name__ == "__main__":
    main()
