"""Draw the skin ROIs on a face image to check the landmark choice visually.

    python python/tools/draw_rois.py                 # uses scikit-image's astronaut photo
    python python/tools/draw_rois.py --image face.jpg --out docs/roi_check.png
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rppg.config import MODEL_PATH, REPO_ROOT, RppgConfig  # noqa: E402
from rppg.face_roi import FaceMesh, roi_mean_rgb, roi_polygons  # noqa: E402

COLORS = {"forehead": (0, 255, 0), "left_cheek": (255, 160, 0), "right_cheek": (0, 160, 255)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", type=Path, help="face image (default: astronaut test photo)")
    ap.add_argument("--out", type=Path, default=REPO_ROOT / "docs" / "roi_check.png")
    args = ap.parse_args()

    if args.image:
        bgr = cv2.imread(str(args.image))
        if bgr is None:
            raise SystemExit(f"cannot read {args.image}")
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    else:
        from skimage import data
        rgb = data.astronaut()

    with FaceMesh(MODEL_PATH) as mesh:
        lm = mesh.detect(rgb, 0.0)
    if lm is None:
        raise SystemExit("no face found")
    polys = roi_polygons(lm, RppgConfig().roi_shrink)
    mean, n = roi_mean_rgb(rgb, polys)

    vis = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    for x, y in lm.astype(int):
        cv2.circle(vis, (x, y), 1, (200, 200, 200), -1)
    for name, poly in polys.items():
        cv2.polylines(vis, [np.round(poly).astype(np.int32)], True, COLORS[name][::-1], 2)
    cv2.putText(vis, f"ROI pixels={n} mean RGB={np.round(mean).astype(int)}", (10, 25),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(args.out), vis)
    print(f"saved {args.out}  pixels={n}  mean_rgb={mean}")


if __name__ == "__main__":
    main()
