"""All tunable parameters in one place.

Mirrored in android/app/src/main/java/com/rppg/vitals/core/RppgConfig.kt.
If you change a default here, change it there too (the golden-file test will catch
differences in the algorithm, but not in defaults that the test does not exercise).
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# Paths can be overridden with environment variables (no machine-specific paths in code).
MODEL_PATH = Path(os.environ.get("RPPG_MODEL_PATH", REPO_ROOT / "models" / "face_landmarker.task"))
DATA_DIR = Path(os.environ.get("RPPG_DATA_DIR", REPO_ROOT / "data"))


@dataclass(frozen=True)
class RppgConfig:
    # --- sampling ---
    fs: float = 30.0                 # signal is resampled to this rate (Hz)
    buffer_sec: float = 35.0         # history kept (must be >= rr_window_sec and hrv_window_sec)
    max_gap_sec: float = 0.5         # face lost longer than this -> buffer is cleared

    # --- heart rate ---
    window_sec: float = 10.0         # HR analysis window
    min_window_sec: float = 6.0      # first HR estimate after this much data
    pos_window_sec: float = 1.6      # POS sliding window (Wang et al. 2017)
    hr_band_hz: tuple[float, float] = (0.7, 3.0)    # 42-180 BPM
    snr_band_hz: tuple[float, float] = (0.7, 4.0)   # band used for the SNR noise estimate
    nfft: int = 4096                 # FFT length (power of two), zero-padding
    snr_min_db: float = 0.0          # below -> "poor", HR hidden
    snr_good_db: float = 3.0         # above -> "good"
    smooth_n: int = 5                # median of last N accepted HR values
    filter_transition_hz: float = 0.2  # cosine taper width of the display band-pass

    # --- motion ---
    motion_window_sec: float = 1.0
    motion_max: float = 0.04         # std of ROI centre / face size above this -> "moving"

    # --- respiration (experimental) ---
    rr_band_hz: tuple[float, float] = (0.1, 0.5)    # 6-30 breaths/min
    rr_window_sec: float = 30.0
    rr_min_window_sec: float = 15.0
    rr_snr_min_db: float = 0.0

    # --- HRV (experimental) ---
    hrv_window_sec: float = 30.0
    hrv_min_beats: int = 15
    hrv_outlier_frac: float = 0.25   # drop beat intervals further than this from the median
    hrv_band_halfwidth_hz: float = 0.6  # beat detection band = HR +- this (keeps breathing-related HRV)

    # --- face ROI ---
    roi_shrink: float = 0.85         # shrink ROI polygons toward their centre (avoid hair/edges)

    def __post_init__(self) -> None:
        if self.nfft & (self.nfft - 1):
            raise ValueError("nfft must be a power of two")
        if self.buffer_sec < max(self.window_sec, self.rr_window_sec, self.hrv_window_sec):
            raise ValueError("buffer_sec must cover the longest analysis window")
