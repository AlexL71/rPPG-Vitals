"""Full pipeline on a synthetic video: real face photo + hidden pulse colour change.

Checks the chain: MediaPipe landmarks -> ROI polygons -> mean RGB (uint8 frames) ->
POS -> heart rate. Skipped if the face model has not been downloaded.
"""
import numpy as np
import pytest

from rppg import RppgProcessor
from rppg.config import MODEL_PATH, RppgConfig
from rppg.synthetic import PBV

pytestmark = pytest.mark.skipif(not MODEL_PATH.is_file(), reason="face model not downloaded")


def test_pipeline_on_synthetic_face_video():
    from skimage import data
    from rppg.face_roi import FaceMesh, face_width, roi_mean_rgb, roi_polygons

    face = data.astronaut().astype(float)
    rng = np.random.default_rng(1)
    fps, seconds, hr_hz = 30.0, 12.0, 1.2          # 72 BPM
    cfg = RppgConfig()
    proc = RppgProcessor(cfg)
    est = None
    with FaceMesh(MODEL_PATH) as mesh:
        for i in range(int(fps * seconds)):
            t = i / fps
            light = 1.0 + 0.03 * np.sin(2 * np.pi * 0.07 * t)
            gain = light * (1.0 + 0.004 * np.sin(2 * np.pi * hr_hz * t) * PBV)
            frame = np.clip(face * gain + rng.normal(0, 2.0, face.shape), 0, 255).astype(np.uint8)
            lm = mesh.detect(frame, t)
            assert lm is not None, f"face lost at frame {i}"
            polys = roi_polygons(lm, cfg.roi_shrink)
            rgb, n = roi_mean_rgb(frame, polys)
            assert n > 500
            c = polys["forehead"].mean(axis=0)
            proc.add(t, rgb, c[0], c[1], face_width(lm))
        est = proc.estimate(now=t)
    assert est.quality == "good"
    assert est.hr_bpm == pytest.approx(72, abs=2)
