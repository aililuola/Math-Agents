from __future__ import annotations

import multiprocessing

from mathproofmesh.desktop.main import run


if __name__ == "__main__":
    multiprocessing.freeze_support()
    raise SystemExit(run())
