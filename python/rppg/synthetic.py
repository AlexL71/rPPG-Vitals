"""Synthetic rPPG traces with known ground truth (for tests and golden files).

This is a test signal, not a physiological model: a PPG-like pulse with slight
heart-rate variability, respiration (head motion + colour), lighting drift,
camera noise and an irregular frame clock with occasional dropped frames.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

# Normalised blood-volume-pulse colour signature of skin (approx., POS paper)
PBV = np.array([0.33, 0.77, 0.53])


@dataclass(frozen=True)
class SyntheticSpec:
    duration_sec: float = 40.0
    fps: float = 30.0
    hr_bpm: float = 72.0
    hr_variation_bpm: float = 3.0    # modulated by breathing (sinus arrhythmia)
    rr_bpm: float = 15.0
    pulse_amp: float = 0.004         # relative colour change (~0.4 %)
    noise: float = 0.0006            # relative per-frame noise after ROI averaging
    drift_amp: float = 0.02          # slow lighting change, same in all channels
    motion_px: float = 1.5           # vertical head motion from breathing
    frame_jitter_sec: float = 0.004
    drop_prob: float = 0.03
    seed: int = 7


def make_trace(spec: SyntheticSpec = SyntheticSpec()) -> dict[str, np.ndarray]:
    """Returns dict with t, r, g, b, cx, cy, size arrays (one row per delivered frame)."""
    rng = np.random.default_rng(spec.seed)
    n = int(spec.duration_sec * spec.fps)
    t = np.arange(n) / spec.fps + rng.normal(0, spec.frame_jitter_sec, n)
    t = np.sort(t - t.min())
    keep = rng.random(n) > spec.drop_prob
    t = t[keep]

    f_rr = spec.rr_bpm / 60.0
    breath = np.sin(2 * np.pi * f_rr * t)
    # instantaneous heart rate varies with breathing; integrate to phase
    hr_inst = (spec.hr_bpm + spec.hr_variation_bpm * breath) / 60.0
    phase = 2 * np.pi * np.concatenate([[0.0], np.cumsum(np.diff(t) * 0.5 * (hr_inst[1:] + hr_inst[:-1]))])
    pulse = np.sin(phase) + 0.35 * np.sin(2 * phase - 0.8)   # PPG-like shape with harmonic
    pulse /= pulse.std()

    base = np.array([170.0, 120.0, 100.0])                    # skin tone (R, G, B)
    drift = spec.drift_amp * np.sin(2 * np.pi * 0.05 * t + 0.3)
    breath_color = 0.002 * breath                              # small intensity change
    rel = (1.0 + drift + breath_color)[:, None] * (1.0 + spec.pulse_amp * pulse[:, None] * PBV)
    rgb = base * rel * (1.0 + rng.normal(0, spec.noise, (t.size, 3)))

    cy = 200.0 + spec.motion_px * breath + rng.normal(0, 0.2, t.size)
    cx = 320.0 + rng.normal(0, 0.2, t.size)
    size = np.full(t.size, 220.0)
    return {"t": t, "r": rgb[:, 0], "g": rgb[:, 1], "b": rgb[:, 2], "cx": cx, "cy": cy, "size": size}
