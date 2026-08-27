#!/usr/bin/env python3
"""Align phase-02 Java sources with the package fixed by the migration map."""

from __future__ import annotations

import os
from pathlib import Path


ROOT = Path(
    os.environ.get(
        "MATHPROOFMESH_TARGET_ROOT",
        Path(__file__).absolute().parents[2],
    )
).absolute()
OLD_PACKAGE = "io.github.aililuola.mathproofmesh.contracts"
NEW_PACKAGE = "io.github.aililuola.mathproofmesh.contract"
OLD_PACKAGE_PATH = "io/github/aililuola/mathproofmesh/contracts"
NEW_PACKAGE_PATH = "io/github/aililuola/mathproofmesh/contract"


def assert_within_root(path: Path) -> None:
    path.resolve().relative_to(ROOT.resolve())


def replace_package_references() -> int:
    candidates = [
        *ROOT.glob("mathproofmesh-*/src/**/*.java"),
        ROOT / "migration/tools/generate_phase02_contracts.py",
    ]
    changed = 0
    for path in candidates:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        updated = text.replace(OLD_PACKAGE, NEW_PACKAGE)
        updated = updated.replace(OLD_PACKAGE_PATH, NEW_PACKAGE_PATH)
        updated = updated.replace("..contracts..", "..contract..")
        if updated != text:
            path.write_text(updated, encoding="utf-8", newline="\n")
            changed += 1
    return changed


def move_package_directory(source_root: Path) -> bool:
    old_dir = source_root / "io/github/aililuola/mathproofmesh/contracts"
    new_dir = source_root / "io/github/aililuola/mathproofmesh/contract"
    assert_within_root(old_dir)
    assert_within_root(new_dir)
    if not old_dir.exists():
        if new_dir.is_dir():
            return False
        raise RuntimeError(f"Neither package directory exists under {source_root}")
    if new_dir.exists():
        raise RuntimeError(f"Refusing to merge package directories: {new_dir}")
    old_dir.rename(new_dir)
    return True


def main() -> None:
    changed = replace_package_references()
    moved = sum(
        move_package_directory(ROOT / "mathproofmesh-contracts" / source_set)
        for source_set in ("src/main/java", "src/test/java")
    )
    print(f"PHASE-02 PACKAGE ALIGNMENT: PASS files_changed={changed} dirs_moved={moved}")


if __name__ == "__main__":
    main()
