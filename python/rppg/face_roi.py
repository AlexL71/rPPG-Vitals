"""Face landmarks -> skin ROIs (forehead, cheeks) -> mean colour.

Uses MediaPipe Face Landmarker (478 landmarks). The same landmark indices are used
on Android (core/FaceRoi.kt), so both versions measure the same skin regions.
"""
from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np

# MediaPipe face-mesh landmark indices. "left"/"right" = image left/right
# (un-mirrored camera image). Polygons are built as convex hulls, so order does not matter.
ROI_LANDMARKS: dict[str, tuple[int, ...]] = {
    "forehead": (109, 10, 338, 297, 332, 334, 296, 336, 9, 107, 66, 105, 103, 67),
    "left_cheek": (116, 117, 118, 119, 100, 142, 203, 206, 205, 50, 123),
    "right_cheek": (345, 346, 347, 348, 329, 371, 423, 426, 425, 280, 352),
}
FACE_LEFT, FACE_RIGHT = 234, 454  # face-oval extremes, used for face width


def convex_hull(points: np.ndarray) -> np.ndarray:
    """Andrew's monotone chain. points (N, 2) -> hull (M, 2), counter-clockwise."""
    pts = sorted(map(tuple, np.asarray(points, dtype=float)))
    if len(pts) <= 2:
        return np.array(pts)

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower: list = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper: list = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return np.array(lower[:-1] + upper[:-1])


def roi_polygons(landmarks_px: np.ndarray, shrink: float = 0.85) -> dict[str, np.ndarray]:
    """Convex hull of each ROI's landmarks, shrunk toward its centroid."""
    polys = {}
    for name, idx in ROI_LANDMARKS.items():
        hull = convex_hull(landmarks_px[list(idx)])
        c = hull.mean(axis=0)
        polys[name] = c + (hull - c) * shrink
    return polys


def face_width(landmarks_px: np.ndarray) -> float:
    return float(np.linalg.norm(landmarks_px[FACE_RIGHT] - landmarks_px[FACE_LEFT]))


def roi_mean_rgb(frame_rgb: np.ndarray, polygons: dict[str, np.ndarray]
                 ) -> tuple[np.ndarray | None, int]:
    """Pixel-count-weighted mean RGB over all ROI polygons. Returns (rgb, n_pixels)."""
    h, w = frame_rgb.shape[:2]
    total = np.zeros(3)
    count = 0
    for poly in polygons.values():
        x0, y0 = np.floor(poly.min(axis=0)).astype(int)
        x1, y1 = np.ceil(poly.max(axis=0)).astype(int)
        x0, y0, x1, y1 = max(x0, 0), max(y0, 0), min(x1, w - 1), min(y1, h - 1)
        if x1 <= x0 or y1 <= y0:
            continue
        mask = np.zeros((y1 - y0 + 1, x1 - x0 + 1), dtype=np.uint8)
        cv2.fillPoly(mask, [np.round(poly - (x0, y0)).astype(np.int32)], 1)
        sel = frame_rgb[y0:y1 + 1, x0:x1 + 1][mask.astype(bool)]
        total += sel.sum(axis=0)
        count += sel.shape[0]
    if count == 0:
        return None, 0
    return total / count, count


class FaceMesh:
    """Thin wrapper around MediaPipe Face Landmarker in VIDEO mode."""

    def __init__(self, model_path: Path | str) -> None:
        import mediapipe as mp
        from mediapipe.tasks import python as mp_tasks
        from mediapipe.tasks.python import vision

        model_path = Path(model_path)
        if not model_path.is_file():
            raise FileNotFoundError(
                f"Face landmarker model not found: {model_path}\n"
                "Run: python python/tools/download_model.py")
        self._mp = mp
        options = vision.FaceLandmarkerOptions(
            base_options=mp_tasks.BaseOptions(model_asset_path=str(model_path)),
            running_mode=vision.RunningMode.VIDEO,
            num_faces=1,
            min_face_detection_confidence=0.5,
            min_face_presence_confidence=0.5,
            min_tracking_confidence=0.5,
        )
        self._landmarker = vision.FaceLandmarker.create_from_options(options)
        self._last_ts = -1

    def detect(self, frame_rgb: np.ndarray, t_sec: float) -> np.ndarray | None:
        """Landmarks in pixel coordinates (478, 2), or None if no face."""
        ts = max(int(t_sec * 1000), self._last_ts + 1)  # VIDEO mode needs increasing ms
        self._last_ts = ts
        image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB,
                               data=np.ascontiguousarray(frame_rgb))
        result = self._landmarker.detect_for_video(image, ts)
        if not result.face_landmarks:
            return None
        h, w = frame_rgb.shape[:2]
        return np.array([(lm.x * w, lm.y * h) for lm in result.face_landmarks[0]])

    def close(self) -> None:
        self._landmarker.close()

    def __enter__(self) -> "FaceMesh":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
