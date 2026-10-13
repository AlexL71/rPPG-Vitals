"""Stateful rPPG processor: collects per-frame samples and produces estimates.

Port: android/.../core/RppgProcessor.kt (same logic, same order of operations).

Usage:
    proc = RppgProcessor()
    for each frame with a face:  proc.add(t, (r, g, b), cx, cy, size)
    once per second:             est = proc.estimate(now=t)
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field

import numpy as np

from . import dsp
from .config import RppgConfig


@dataclass
class Sample:
    t: float      # seconds
    r: float      # mean ROI colour (0-255)
    g: float
    b: float
    cx: float     # ROI centre, pixels
    cy: float
    size: float   # face width, pixels


@dataclass
class Estimate:
    t: float
    data_sec: float
    quality: str                       # no_face | warming_up | moving | poor | fair | good
    hr_bpm: float | None = None        # smoothed (median of accepted values); None when poor
    hr_raw_bpm: float | None = None    # this window's spectral peak (always, if data)
    snr_db: float | None = None
    motion: float | None = None
    rr_bpm: float | None = None        # respiration (experimental)
    rr_snr_db: float | None = None
    rr_source: str | None = None       # "motion" or "color"
    rmssd_ms: float | None = None      # HRV (experimental)
    sdnn_ms: float | None = None
    n_beats: int | None = None
    waveform: np.ndarray = field(default_factory=lambda: np.zeros(0))


class RppgProcessor:
    def __init__(self, cfg: RppgConfig | None = None) -> None:
        self.cfg = cfg or RppgConfig()
        self._buf: deque[Sample] = deque()
        self._history: deque[float] = deque(maxlen=self.cfg.smooth_n)

    # ------------------------------------------------------------------ input
    def reset(self) -> None:
        self._buf.clear()
        self._history.clear()

    def add(self, t: float, rgb: tuple[float, float, float], cx: float, cy: float,
            size: float) -> None:
        if self._buf and t <= self._buf[-1].t:
            return  # out-of-order / duplicate timestamp
        if self._buf and t - self._buf[-1].t > self.cfg.max_gap_sec:
            self.reset()  # face was lost: start a new measurement
        self._buf.append(Sample(t, float(rgb[0]), float(rgb[1]), float(rgb[2]), cx, cy, size))
        while self._buf and self._buf[0].t < t - self.cfg.buffer_sec:
            self._buf.popleft()

    @property
    def data_sec(self) -> float:
        return self._buf[-1].t - self._buf[0].t if len(self._buf) > 1 else 0.0

    # --------------------------------------------------------------- analysis
    def estimate(self, now: float | None = None) -> Estimate:
        cfg = self.cfg
        if not self._buf or (now is not None and now - self._buf[-1].t > cfg.max_gap_sec):
            return Estimate(t=now or 0.0, data_sec=0.0, quality="no_face")
        t_last = self._buf[-1].t
        data_sec = self.data_sec
        if data_sec < cfg.min_window_sec:
            return Estimate(t=t_last, data_sec=data_sec, quality="warming_up")

        arr = np.array([(s.t, s.r, s.g, s.b, s.cx, s.cy, s.size) for s in self._buf])
        tu, xu = dsp.resample_uniform(arr[:, 0], arr[:, 1:], cfg.fs)
        rgb, cx, cy, size = xu[:, 0:3], xu[:, 3], xu[:, 4], xu[:, 5]

        pulse = dsp.pos(rgb, cfg.fs, cfg.pos_window_sec)
        n_win = min(len(pulse), int(round(cfg.window_sec * cfg.fs)))
        seg = pulse[-n_win:]

        # heart rate from the spectrum of the last window
        f, p = dsp.power_spectrum(seg, cfg.fs, cfg.nfft)
        f0 = dsp.find_peak_hz(f, p, cfg.hr_band_hz)
        est = Estimate(t=t_last, data_sec=data_sec, quality="poor")
        if f0 is None:
            return est
        halfwidth = 2.0 * cfg.fs / n_win  # Hann main-lobe half width
        est.hr_raw_bpm = f0 * 60.0
        est.snr_db = dsp.snr_db(f, p, f0, halfwidth, cfg.snr_band_hz)

        # motion: spread of the ROI centre during the last second, relative to face size
        n_mot = max(2, int(round(cfg.motion_window_sec * cfg.fs)))
        mean_size = float(np.mean(size[-n_mot:]))
        est.motion = float(np.sqrt(np.var(cx[-n_mot:]) + np.var(cy[-n_mot:])) / mean_size) \
            if mean_size > 0 else 0.0

        if est.motion > cfg.motion_max:
            est.quality = "moving"
        elif est.snr_db >= cfg.snr_good_db:
            est.quality = "good"
        elif est.snr_db >= cfg.snr_min_db:
            est.quality = "fair"
        else:
            est.quality = "poor"

        if est.quality in ("good", "fair"):
            self._history.append(est.hr_raw_bpm)
        if self._history and est.quality != "poor":
            est.hr_bpm = float(np.median(self._history))

        # waveform for display (normalised)
        wave = dsp.bandpass_fft(seg, cfg.fs, cfg.hr_band_hz, cfg.filter_transition_hz)
        sd = wave.std()
        est.waveform = wave / sd if sd > 0 else wave

        self._respiration(est, tu, rgb[:, 1], cy)
        if est.quality in ("good", "fair"):
            self._hrv(est, pulse, f0)
        return est

    def _respiration(self, est: Estimate, tu: np.ndarray, green: np.ndarray,
                     cy: np.ndarray) -> None:
        cfg = self.cfg
        n = min(len(tu), int(round(cfg.rr_window_sec * cfg.fs)))
        if n / cfg.fs < cfg.rr_min_window_sec:
            return
        halfwidth = 2.0 * cfg.fs / n
        best: tuple[float, float, str] | None = None
        g = green[-n:]
        g_mean = g.mean()
        candidates = [("motion", cy[-n:])]
        if g_mean > 0:
            candidates.append(("color", g / g_mean))
        for name, sig in candidates:
            f, p = dsp.power_spectrum(sig, cfg.fs, cfg.nfft)
            f0 = dsp.find_peak_hz(f, p, cfg.rr_band_hz)
            if f0 is None:
                continue
            snr = dsp.snr_db(f, p, f0, halfwidth, cfg.rr_band_hz)
            if best is None or snr > best[1]:
                best = (f0 * 60.0, snr, name)
        if best is not None and best[1] >= cfg.rr_snr_min_db:
            est.rr_bpm, est.rr_snr_db, est.rr_source = best

    def _hrv(self, est: Estimate, pulse: np.ndarray, f0: float) -> None:
        cfg = self.cfg
        n = int(round(cfg.hrv_window_sec * cfg.fs))
        if len(pulse) < n:
            return
        # narrow band around the current heart rate: removes noise that creates extra/missed beats
        band = (max(cfg.hr_band_hz[0], f0 - cfg.hrv_band_halfwidth_hz),
                min(cfg.hr_band_hz[1], f0 + cfg.hrv_band_halfwidth_hz))
        y = dsp.bandpass_fft(pulse[-n:], cfg.fs, band, cfg.filter_transition_hz)
        beats = dsp.detect_beats(y, cfg.fs, f0)
        res = dsp.hrv_from_beats(beats, cfg.hrv_min_beats, cfg.hrv_outlier_frac)
        if res is not None:
            est.rmssd_ms, est.sdnn_ms, est.n_beats = res["rmssd_ms"], res["sdnn_ms"], res["n_beats"]
