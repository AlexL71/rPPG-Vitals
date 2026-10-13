"""Live rPPG demo with a webcam (or a video file).

    python python/live_demo.py                      # webcam 0
    python python/live_demo.py --camera 1
    python python/live_demo.py --video clip.avi     # offline, uncompressed video works best

Keys:  q / Esc quit   s start/stop saving session CSV   r reset
       e toggle auto-exposure (driver dependent, DirectShow only)
"""
from __future__ import annotations

import argparse
import time
from datetime import datetime
from pathlib import Path

import cv2
import numpy as np

from rppg import RppgConfig, RppgProcessor
from rppg.config import DATA_DIR, MODEL_PATH
from rppg.face_roi import FaceMesh, face_width, roi_mean_rgb, roi_polygons
from rppg.session_io import SessionWriter

QUALITY_COLORS = {  # BGR
    "good": (80, 200, 80), "fair": (0, 200, 255), "poor": (60, 60, 230),
    "moving": (0, 140, 255), "warming_up": (200, 200, 200), "no_face": (120, 120, 120),
}
STATUS_TEXT = {
    "no_face": "No face - look at the camera", "warming_up": "Measuring... hold still",
    "moving": "Hold still", "poor": "Low signal - more light / hold still",
    "fair": "OK", "good": "Good signal",
}
ROI_COLORS = {"forehead": (0, 255, 0), "left_cheek": (0, 160, 255), "right_cheek": (255, 160, 0)}


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--video", type=Path, help="process a video file instead of the camera")
    ap.add_argument("--width", type=int, default=640)
    ap.add_argument("--height", type=int, default=480)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--landmark-every", type=int, default=1, help="run face landmarks every N frames")
    ap.add_argument("--model", type=Path, default=MODEL_PATH)
    ap.add_argument("--save-dir", type=Path, default=DATA_DIR / "sessions")
    ap.add_argument("--no-display", action="store_true", help="print estimates only")
    return ap.parse_args()


def open_capture(args: argparse.Namespace) -> cv2.VideoCapture:
    if args.video:
        cap = cv2.VideoCapture(str(args.video))
    else:
        cap = cv2.VideoCapture(args.camera, cv2.CAP_DSHOW)  # DirectShow exposes exposure controls
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, args.width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, args.height)
        cap.set(cv2.CAP_PROP_FPS, args.fps)
    if not cap.isOpened():
        raise SystemExit(f"cannot open {'video ' + str(args.video) if args.video else 'camera ' + str(args.camera)}")
    return cap


def draw_waveform(img: np.ndarray, wave: np.ndarray, color) -> None:
    h, w = img.shape[:2]
    top, height = h - 90, 80
    cv2.rectangle(img, (0, top - 5), (w, h), (30, 30, 30), -1)
    if wave.size < 2:
        return
    y = np.clip(wave, -3, 3)
    xs = np.linspace(5, w - 5, y.size)
    ys = top + height / 2 - y / 3 * (height / 2)
    cv2.polylines(img, [np.column_stack([xs, ys]).astype(np.int32)], False, color, 2, cv2.LINE_AA)


def draw_panel(img: np.ndarray, est, fps: float, saving: bool) -> None:
    color = QUALITY_COLORS[est.quality]
    hr = f"{est.hr_bpm:.0f}" if est.hr_bpm is not None else "--"
    cv2.putText(img, f"{hr} BPM", (10, 45), cv2.FONT_HERSHEY_SIMPLEX, 1.4, color, 3, cv2.LINE_AA)
    lines = [STATUS_TEXT[est.quality]]
    if est.snr_db is not None:
        lines.append(f"SNR {est.snr_db:+.1f} dB   raw {est.hr_raw_bpm:.1f}")
    if est.rr_bpm is not None:
        lines.append(f"Resp {est.rr_bpm:.0f}/min ({est.rr_source})  [exp]")
    if est.rmssd_ms is not None:
        lines.append(f"HRV RMSSD {est.rmssd_ms:.0f} ms  SDNN {est.sdnn_ms:.0f} ms  [exp]")
    lines.append(f"{fps:.1f} fps   data {est.data_sec:.0f}s" + ("   REC" if saving else ""))
    for i, text in enumerate(lines):
        cv2.putText(img, text, (10, 75 + 22 * i), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1, cv2.LINE_AA)
    draw_waveform(img, est.waveform, color)


def main() -> None:
    args = parse_args()
    cfg = RppgConfig()
    proc = RppgProcessor(cfg)
    cap = open_capture(args)
    is_file = args.video is not None
    file_fps = cap.get(cv2.CAP_PROP_FPS) or args.fps
    writer: SessionWriter | None = None
    auto_exposure = True
    est = proc.estimate(now=0.0)
    polys, lm, frame_idx = None, None, 0
    t_start = time.perf_counter()
    next_est, fps_t, fps_n, fps = 1.0, 0.0, 0, 0.0

    with FaceMesh(args.model) as mesh:
        while True:
            ok, bgr = cap.read()
            if not ok:
                break
            t = frame_idx / file_fps if is_file else time.perf_counter() - t_start
            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)

            if frame_idx % args.landmark_every == 0 or lm is None:
                lm = mesh.detect(rgb, t)
                polys = roi_polygons(lm, cfg.roi_shrink) if lm is not None else None
            if polys is not None:
                mean, n = roi_mean_rgb(rgb, polys)
                if mean is not None:
                    c = polys["forehead"].mean(axis=0)
                    size = face_width(lm)
                    proc.add(t, mean, c[0], c[1], size)
                    if writer:
                        writer.write(t, mean, c[0], c[1], size)

            if t >= next_est:
                est = proc.estimate(now=t)
                next_est = t + 1.0
                if args.no_display:
                    hr = f"{est.hr_bpm:.1f}" if est.hr_bpm is not None else "--"
                    print(f"t={t:6.1f}s  HR={hr:>5}  raw={est.hr_raw_bpm or 0:6.1f}  "
                          f"SNR={est.snr_db if est.snr_db is not None else float('nan'):+5.1f}  "
                          f"{est.quality:<10} RR={est.rr_bpm or 0:4.1f}  RMSSD={est.rmssd_ms or 0:5.1f}")

            fps_n += 1
            if t - fps_t >= 1.0:
                fps, fps_t, fps_n = fps_n / (t - fps_t), t, 0
            frame_idx += 1

            if args.no_display:
                continue
            vis = cv2.flip(bgr, 1) if not is_file else bgr.copy()  # mirror like a selfie
            if polys is not None:
                w = vis.shape[1]
                for name, poly in polys.items():
                    p = poly.copy()
                    if not is_file:
                        p[:, 0] = w - 1 - p[:, 0]
                    cv2.polylines(vis, [np.round(p).astype(np.int32)], True, ROI_COLORS[name], 1, cv2.LINE_AA)
            draw_panel(vis, est, fps, writer is not None)
            cv2.imshow("rPPG demo (q to quit)", vis)
            key = cv2.waitKey(1) & 0xFF
            if key in (ord("q"), 27):
                break
            if key == ord("r"):
                proc.reset()
            if key == ord("s"):
                if writer:
                    writer.close()
                    print(f"saved {writer.path}")
                    writer = None
                else:
                    path = args.save_dir / f"session_{datetime.now():%Y%m%d_%H%M%S}.csv"
                    writer = SessionWriter(path, {"source": "python_live_demo",
                                                  "camera": str(args.video or args.camera)})
                    print(f"recording to {path}")
            if key == ord("e") and not is_file:
                auto_exposure = not auto_exposure
                # DirectShow convention: 0.75 = auto, 0.25 = manual (keeps current exposure on most drivers)
                cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 0.75 if auto_exposure else 0.25)
                print(f"auto exposure {'ON' if auto_exposure else 'OFF (locked)'}")

    if writer:
        writer.close()
        print(f"saved {writer.path}")
    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
