"""Session CSV format shared by the Python demo and the Android app.

Columns: t_sec,r,g,b,cx,cy,size   (one row per frame with a detected face)
Lines starting with '#' are metadata comments (device, fps, reference values...).
"""
from __future__ import annotations

from pathlib import Path
from typing import TextIO

import numpy as np

COLUMNS = ("t_sec", "r", "g", "b", "cx", "cy", "size")


class SessionWriter:
    def __init__(self, path: Path, meta: dict[str, str] | None = None) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self._f: TextIO = open(path, "w", encoding="utf-8", newline="\n")
        for k, v in (meta or {}).items():
            self._f.write(f"# {k}={v}\n")
        self._f.write(",".join(COLUMNS) + "\n")

    def write(self, t: float, rgb, cx: float, cy: float, size: float) -> None:
        self._f.write(f"{t:.6f},{rgb[0]:.4f},{rgb[1]:.4f},{rgb[2]:.4f},{cx:.2f},{cy:.2f},{size:.2f}\n")

    def close(self) -> None:
        self._f.close()


def read_session(path: Path) -> tuple[dict[str, np.ndarray], dict[str, str]]:
    meta: dict[str, str] = {}
    rows: list[list[float]] = []
    header: list[str] | None = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#"):
                if "=" in line:
                    k, v = line[1:].split("=", 1)
                    meta[k.strip()] = v.strip()
                continue
            if header is None:
                header = line.split(",")
                missing = set(COLUMNS) - set(header)
                if missing:
                    raise ValueError(f"{path}: missing columns {sorted(missing)}")
                continue
            rows.append([float(v) for v in line.split(",")])
    if header is None or not rows:
        raise ValueError(f"{path}: no data rows")
    data = np.array(rows)
    return {name: data[:, header.index(name)] for name in COLUMNS}, meta
