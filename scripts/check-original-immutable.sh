#!/usr/bin/env sh
set -eu

target_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace_root=$(CDPATH= cd -- "$target_root/.." && pwd)

python3 - "$workspace_root" "$target_root" <<'PY'
import csv
import hashlib
import os
import pathlib
import sys

workspace = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
baseline_path = target / "migration" / "baseline" / "source-manifest.csv"
expected_manifest_path = target / "SOURCE_SNAPSHOT_SHA256SUMS.txt"
expected_combined = "9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770"

with baseline_path.open("r", encoding="utf-8", newline="") as handle:
    baseline = {row["path"]: row for row in csv.DictReader(handle)}
if len(baseline) != 401:
    raise SystemExit(f"Frozen baseline has {len(baseline)} rows; expected 401")

current = {}
for root, directories, files in os.walk(workspace):
    root_path = pathlib.Path(root)
    directories[:] = [
        name
        for name in directories
        if name != ".git" and (root_path / name).resolve() != target.resolve()
    ]
    for name in files:
        path = root_path / name
        relative = path.relative_to(workspace).as_posix()
        if ".git" in pathlib.PurePosixPath(relative).parts:
            continue
        current[relative] = path

failures = []
if len(current) != 401:
    failures.append(f"Current source file count is {len(current)}; expected 401")
for relative, row in baseline.items():
    path = current.get(relative)
    if path is None:
        failures.append(f"Missing source file: {relative}")
        continue
    if path.stat().st_size != int(row["size_bytes"]):
        failures.append(f"Size mismatch: {relative}")
        continue
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != row["sha256"]:
        failures.append(f"SHA-256 mismatch: {relative}")
for relative in sorted(set(current) - set(baseline)):
    failures.append(f"Unexpected source file outside TARGET_ROOT: {relative}")

lines = []
for relative in sorted(current, key=lambda value: value.encode("utf-8")):
    digest = hashlib.sha256(current[relative].read_bytes()).hexdigest()
    lines.append(f"{digest}  {relative}\n")
manifest_bytes = "".join(lines).encode("utf-8")
combined = hashlib.sha256(manifest_bytes).hexdigest()
if combined != expected_combined:
    failures.append(
        f"Combined manifest mismatch: expected={expected_combined} actual={combined}"
    )
if manifest_bytes != expected_manifest_path.read_bytes():
    failures.append("Generated manifest bytes differ from the authoritative manifest")
if failures:
    raise SystemExit("\n".join(failures))
print("SOURCE IMMUTABILITY: PASS")
print("files=401")
print(f"manifest_sha256={combined}")
PY
