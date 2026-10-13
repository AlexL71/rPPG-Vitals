"""Signal processing for rPPG. Pure numpy, no state.

Every function here has a 1:1 port in android/.../core/Dsp.kt.
Keep the two in sync; tests/test_golden.py + the Kotlin GoldenTest check it.
"""
from __future__ import annotations

import numpy as np

# POS projection matrix (Wang et al. 2017): S1 = G - B, S2 = -2R + G + B
POS_P = np.array([[0.0, 1.0, -1.0], [-2.0, 1.0, 1.0]])


def next_pow2(n: int) -> int:
    p = 1
    while p < n:
        p *= 2
    return p


def resample_uniform(t: np.ndarray, x: np.ndarray, fs: float) -> tuple[np.ndarray, np.ndarray]:
    """Linear interpolation of (t, x) onto a uniform grid t[0] + k/fs.

    x may be 1-D (N,) or 2-D (N, C). Real cameras do not deliver frames at exactly
    30 fps, so every analysis works on the resampled signal.
    """
    t = np.asarray(t, dtype=float)
    x = np.asarray(x, dtype=float)
    n = int(np.floor((t[-1] - t[0]) * fs)) + 1
    tu = t[0] + np.arange(n) / fs
    if x.ndim == 1:
        return tu, np.interp(tu, t, x)
    return tu, np.column_stack([np.interp(tu, t, x[:, c]) for c in range(x.shape[1])])


def pos(rgb: np.ndarray, fs: float, win_sec: float = 1.6) -> np.ndarray:
    """Plane-Orthogonal-to-Skin (POS) pulse extraction.

    rgb: (N, 3) mean skin colour per frame (uniformly sampled).
    Returns the pulse signal (N,). Each 1.6 s window is normalised by its mean
    colour (removes lighting level), projected onto two axes orthogonal to the skin
    tone, combined with a ratio that cancels the common distortion, and added
    (overlap-add) into the output.
    """
    rgb = np.asarray(rgb, dtype=float)
    n = rgb.shape[0]
    win = int(round(win_sec * fs))
    out = np.zeros(n)
    if n < win or win < 2:
        return out
    for start in range(n - win + 1):
        c = rgb[start:start + win]
        mean = c.mean(axis=0)
        if np.any(mean <= 0):
            continue
        s = (c / mean) @ POS_P.T
        s1, s2 = s[:, 0], s[:, 1]
        std2 = s2.std()
        alpha = s1.std() / std2 if std2 > 1e-12 else 0.0
        h = s1 + alpha * s2
        out[start:start + win] += h - h.mean()
    return out


def detrend_linear(x: np.ndarray) -> np.ndarray:
    """Remove the least-squares straight line."""
    x = np.asarray(x, dtype=float)
    n = x.size
    if n < 2:
        return x - x.mean() if n else x
    tt = np.arange(n, dtype=float)
    tm, xm = tt.mean(), x.mean()
    slope = np.sum((tt - tm) * (x - xm)) / np.sum((tt - tm) ** 2)
    return x - (xm + slope * (tt - tm))


def hann(n: int) -> np.ndarray:
    """Symmetric Hann window (same formula as the Kotlin port)."""
    if n == 1:
        return np.ones(1)
    k = np.arange(n)
    return 0.5 - 0.5 * np.cos(2.0 * np.pi * k / (n - 1))


def power_spectrum(x: np.ndarray, fs: float, nfft: int) -> tuple[np.ndarray, np.ndarray]:
    """Detrend -> Hann window -> zero-padded FFT -> power. Returns (freqs, power)."""
    x = detrend_linear(x) * hann(len(x))
    n_fft = max(nfft, next_pow2(len(x)))
    spec = np.fft.rfft(x, n_fft)
    return np.fft.rfftfreq(n_fft, 1.0 / fs), spec.real ** 2 + spec.imag ** 2


def parabolic_offset(a: float, b: float, c: float) -> float:
    """Sub-bin offset of a peak from its two neighbours, clipped to [-0.5, 0.5]."""
    denom = a - 2.0 * b + c
    if denom == 0.0:
        return 0.0
    return float(np.clip(0.5 * (a - c) / denom, -0.5, 0.5))


def find_peak_hz(f: np.ndarray, p: np.ndarray, band: tuple[float, float]) -> float | None:
    """Frequency of the highest spectral peak inside band (with parabolic refinement).

    Only true local maxima count. Slow drift often rises toward the low band edge;
    without this rule the edge bin (0.7 Hz = 42 BPM) would win although it is not a peak.
    """
    best = -1
    for k in np.flatnonzero((f >= band[0]) & (f <= band[1])):
        if 0 < k < len(p) - 1 and p[k] > p[k - 1] and p[k] >= p[k + 1]:
            if best < 0 or p[k] > p[best]:
                best = int(k)
    if best < 0:
        return None
    delta = parabolic_offset(p[best - 1], p[best], p[best + 1])
    return float(f[best] + delta * (f[1] - f[0]))


def snr_db(f: np.ndarray, p: np.ndarray, f0: float, halfwidth: float,
           band: tuple[float, float]) -> float:
    """SNR as in de Haan & Jeanne 2013: power near f0 and 2*f0 vs the rest of band."""
    in_band = (f >= band[0]) & (f <= band[1])
    sig = in_band & ((np.abs(f - f0) <= halfwidth) | (np.abs(f - 2.0 * f0) <= halfwidth))
    ps = float(p[sig].sum())
    pn = float(p[in_band & ~sig].sum())
    if ps <= 0.0:
        return -99.0
    if pn <= 0.0:
        return 99.0
    return float(10.0 * np.log10(ps / pn))


def bandpass_fft(x: np.ndarray, fs: float, band: tuple[float, float],
                 transition_hz: float = 0.2) -> np.ndarray:
    """Zero-phase band-pass via FFT with cosine-tapered edges (for display and beats)."""
    x = detrend_linear(x)
    n = x.size
    if n < 4:
        return np.zeros(n)
    n_fft = next_pow2(2 * n)  # zero-pad to limit circular wrap-around
    spec = np.fft.rfft(x, n_fft)
    f = np.fft.rfftfreq(n_fft, 1.0 / fs)
    lo, hi, tw = band[0], band[1], transition_hz
    gain = np.zeros_like(f)
    gain[(f >= lo) & (f <= hi)] = 1.0
    rise = (f > lo - tw) & (f < lo)
    gain[rise] = 0.5 - 0.5 * np.cos(np.pi * (f[rise] - (lo - tw)) / tw)
    fall = (f > hi) & (f < hi + tw)
    gain[fall] = 0.5 + 0.5 * np.cos(np.pi * (f[fall] - hi) / tw)
    return np.fft.irfft(spec * gain, n_fft)[:n]


def detect_beats(y: np.ndarray, fs: float, hr_hz: float) -> np.ndarray:
    """Systolic peak times (s, sub-sample) in a band-passed pulse signal.

    A sample is a beat if it is positive and the maximum within +-0.6 beat periods.
    """
    n = y.size
    if n < 3 or hr_hz <= 0:
        return np.zeros(0)
    half = max(1, int(np.floor(0.6 * fs / hr_hz)))
    times = []
    for i in range(1, n - 1):
        if y[i] <= 0 or y[i] < y[i - 1] or y[i] < y[i + 1]:
            continue
        lo, hi = max(0, i - half), min(n, i + half + 1)
        if y[i] < y[lo:hi].max():
            continue
        if times and (i - times[-1][0]) <= half:  # plateau: keep the first sample
            continue
        times.append((i, i + parabolic_offset(y[i - 1], y[i], y[i + 1])))
    return np.array([pos_ for _, pos_ in times]) / fs


def hrv_from_beats(beat_times: np.ndarray, min_beats: int,
                   outlier_frac: float = 0.25) -> dict | None:
    """RMSSD and SDNN (ms) from beat times, after removing outlier intervals."""
    if beat_times.size < min_beats:
        return None
    ibi = np.diff(beat_times)
    med = float(np.median(ibi))
    ok = np.abs(ibi - med) <= outlier_frac * med
    if int(ok.sum()) < min_beats - 1:
        return None
    good = ibi[ok]
    pair_ok = ok[:-1] & ok[1:]
    diffs = np.diff(ibi)[pair_ok]
    if diffs.size < 2:
        return None
    return {
        "rmssd_ms": float(np.sqrt(np.mean(diffs ** 2)) * 1000.0),
        "sdnn_ms": float(np.std(good, ddof=1) * 1000.0),
        "n_beats": int(beat_times.size),
        "mean_hr_bpm": float(60.0 / good.mean()),
    }
