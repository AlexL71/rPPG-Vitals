"""Replay a session CSV (from the Python demo or the Android app) through the processor.

    python python/analyze_csv.py data/sessions/session_20261014_210000.csv
    python python/analyze_csv.py session.csv --reference-bpm 74 --plot docs/session.png
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

from rppg import RppgConfig, RppgProcessor
from rppg import dsp
from rppg.session_io import read_session


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", type=Path)
    ap.add_argument("--step", type=float, default=1.0, help="seconds between estimates")
    ap.add_argument("--reference-bpm", type=float, help="reference HR (oximeter/watch) for error")
    ap.add_argument("--plot", type=Path, help="save a PNG with HR over time and the last spectrum")
    args = ap.parse_args()

    data, meta = read_session(args.csv)
    t = data["t_sec"]
    dt = np.diff(t)
    print(f"{args.csv.name}: {t.size} frames, {t[-1] - t[0]:.1f} s, "
          f"median fps {1 / np.median(dt):.1f}, gaps>0.1s: {int((dt > 0.1).sum())}")
    for k, v in meta.items():
        print(f"  # {k} = {v}")

    cfg = RppgConfig()
    proc = RppgProcessor(cfg)
    rows, next_t = [], t[0] + args.step
    for i in range(t.size):
        proc.add(t[i], (data["r"][i], data["g"][i], data["b"][i]), data["cx"][i], data["cy"][i], data["size"][i])
        if t[i] >= next_t:
            e = proc.estimate(now=t[i])
            rows.append(e)
            next_t += args.step

    print(f"\n{'t':>6} {'HR':>6} {'raw':>6} {'SNR':>6} {'quality':<10} {'RR':>5} {'RMSSD':>6}")
    for e in rows:
        print(f"{e.t - t[0]:6.1f} {e.hr_bpm or float('nan'):6.1f} {e.hr_raw_bpm or float('nan'):6.1f} "
              f"{e.snr_db if e.snr_db is not None else float('nan'):6.1f} {e.quality:<10} "
              f"{e.rr_bpm or float('nan'):5.1f} {e.rmssd_ms or float('nan'):6.1f}")

    hrs = np.array([e.hr_bpm for e in rows if e.hr_bpm is not None])
    if hrs.size:
        print(f"\nHR shown: mean {hrs.mean():.1f}, median {np.median(hrs):.1f}, n={hrs.size}")
        if args.reference_bpm:
            err = hrs - args.reference_bpm
            print(f"vs reference {args.reference_bpm:.1f}: MAE {np.abs(err).mean():.2f} BPM, "
                  f"RMSE {np.sqrt((err ** 2).mean()):.2f} BPM")
    else:
        print("\nno HR values passed the quality gate")

    if args.plot:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt

        _, xu = dsp.resample_uniform(t, np.column_stack([data["r"], data["g"], data["b"]]), cfg.fs)
        seg = dsp.pos(xu, cfg.fs, cfg.pos_window_sec)[-int(cfg.window_sec * cfg.fs):]
        f, p = dsp.power_spectrum(seg, cfg.fs, cfg.nfft)
        fig, (a1, a2) = plt.subplots(2, 1, figsize=(9, 6))
        a1.plot([e.t - t[0] for e in rows], [e.hr_raw_bpm or np.nan for e in rows], ".", label="raw window HR")
        a1.plot([e.t - t[0] for e in rows], [e.hr_bpm or np.nan for e in rows], "-", label="shown HR")
        if args.reference_bpm:
            a1.axhline(args.reference_bpm, color="k", ls="--", label="reference")
        a1.set(xlabel="time (s)", ylabel="BPM", title=args.csv.name)
        a1.legend()
        band = (f >= 0.5) & (f <= 4.0)
        a2.plot(f[band] * 60, p[band])
        a2.set(xlabel="BPM", ylabel="power", title="spectrum of last window")
        fig.tight_layout()
        args.plot.parent.mkdir(parents=True, exist_ok=True)
        fig.savefig(args.plot, dpi=110)
        print(f"plot saved: {args.plot}")


if __name__ == "__main__":
    main()
