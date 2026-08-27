#!/usr/bin/env python3
"""Verify the phase-17 migration and release closure."""

from __future__ import annotations

import csv
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "migration" / "reports" / "phase-17-gates.json"
EXPECTED_ZIP_SHA256 = (
    "5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2"
)
EXPECTED_MANIFEST_SHA256 = (
    "9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770"
)
AUTHORITY_ZIP = (
    ROOT
    / "migration"
    / "input"
    / "Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control.zip"
)
SOURCE_MANIFEST = ROOT / "SOURCE_SNAPSHOT_SHA256SUMS.txt"
RELEASE_ROOT = ROOT / "target" / "release"
BUNDLE = RELEASE_ROOT / "JavaMathProofMesh-0.8.0"


def native_path(path: Path) -> str:
    value = str(path.resolve())
    if os.name == "nt" and not value.startswith("\\\\?\\"):
        return "\\\\?\\" + value
    return value


def is_file(path: Path) -> bool:
    return os.path.isfile(native_path(path))


def is_dir(path: Path) -> bool:
    return os.path.isdir(native_path(path))


def read_text(
    path: Path,
    *,
    encoding: str = "utf-8-sig",
    errors: str = "strict",
) -> str:
    with open(native_path(path), encoding=encoding, errors=errors) as stream:
        return stream.read()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(native_path(path), "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def load_json(path: Path) -> dict[str, Any]:
    with open(native_path(path), encoding="utf-8-sig") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError(f"{relative(path)} must contain a JSON object")
    return value


def gate(result: bool, **details: Any) -> dict[str, Any]:
    return {"result": "PASS" if result else "FAIL", **details}


def read_map(
    path: Path,
    path_column: str,
    expected_count: int,
    accepted_statuses: set[str],
) -> tuple[dict[str, Any], set[str]]:
    with open(native_path(path), encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    source_paths = [row.get(path_column, "").replace("\\", "/") for row in rows]
    statuses = [row.get("status", "") for row in rows]
    duplicate_paths = sorted(
        {item for item in source_paths if source_paths.count(item) > 1}
    )
    invalid_statuses = sorted(
        {
            status
            for status in statuses
            if status.strip().lower() not in accepted_statuses
        }
    )
    valid = (
        len(rows) == expected_count
        and all(source_paths)
        and not duplicate_paths
        and not invalid_statuses
    )
    return (
        gate(
            valid,
            path=relative(path),
            rows=len(rows),
            expected_rows=expected_count,
            accepted_statuses=sorted(accepted_statuses),
            observed_statuses=sorted(set(statuses)),
            duplicate_paths=duplicate_paths,
            invalid_statuses=invalid_statuses,
        ),
        set(source_paths),
    )


def read_manifest_paths() -> set[str]:
    paths: set[str] = set()
    for line in read_text(SOURCE_MANIFEST, encoding="utf-8").splitlines():
        match = re.fullmatch(r"[0-9a-f]{64}  (.+)", line)
        if not match:
            raise ValueError(f"Malformed frozen-manifest line: {line!r}")
        paths.add(match.group(1).replace("\\", "/"))
    return paths


def report_gate(path: Path) -> dict[str, Any]:
    if not is_file(path):
        return gate(False, path=relative(path), error="missing")
    try:
        payload = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return gate(False, path=relative(path), error=str(error))
    return gate(
        payload.get("result") == "PASS",
        path=relative(path),
        recorded_result=payload.get("result"),
        sha256=sha256(path),
    )


def verify_test_reports() -> dict[str, Any]:
    reports = sorted((ROOT / "target" / "modules").rglob("TEST-*.xml"))
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    invalid: list[dict[str, Any]] = []
    for path in reports:
        try:
            root = ET.parse(native_path(path)).getroot()
            values = {
                key: int(float(root.attrib.get(key, "0")))
                for key in totals
            }
        except (OSError, ValueError, ET.ParseError) as error:
            invalid.append({"path": relative(path), "error": str(error)})
            continue
        for key, value in values.items():
            totals[key] += value
        if any(values[key] for key in ("failures", "errors", "skipped")):
            invalid.append({"path": relative(path), **values})
    return gate(bool(reports) and not invalid, report_files=len(reports), **totals, invalid=invalid)


def verify_log(path: Path) -> dict[str, Any]:
    if not is_file(path):
        return gate(False, path=relative(path), error="missing")
    text = read_text(path, errors="replace")
    full_pass = "FULL VERIFICATION: PASS" in text
    exit_zero = re.search(r"(?m)^exit_code=0\s*$", text) is not None
    return gate(
        full_pass and exit_zero,
        path=relative(path),
        full_verification_pass=full_pass,
        exit_code_zero=exit_zero,
        sha256=sha256(path),
    )


def parse_checksum_file(path: Path) -> tuple[dict[str, str], list[str]]:
    checksums: dict[str, str] = {}
    malformed: list[str] = []
    for line in read_text(path, encoding="ascii").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match:
            malformed.append(line)
            continue
        checksums[match.group(2).replace("\\", "/")] = match.group(1)
    return checksums, malformed


def verify_bundle_checksums() -> dict[str, Any]:
    checksum_path = BUNDLE / "SHA256SUMS.txt"
    if not is_file(checksum_path):
        return gate(False, error="bundle SHA256SUMS.txt is missing")
    expected, malformed = parse_checksum_file(checksum_path)
    actual = {
        path.relative_to(BUNDLE).as_posix(): sha256(path)
        for path in BUNDLE.rglob("*")
        if is_file(path) and path != checksum_path
    }
    missing = sorted(set(actual) - set(expected))
    extra = sorted(set(expected) - set(actual))
    mismatches = sorted(
        path
        for path in set(actual) & set(expected)
        if actual[path] != expected[path]
    )
    return gate(
        not malformed and not missing and not extra and not mismatches,
        files=len(actual),
        malformed_lines=malformed,
        unlisted_files=missing,
        missing_files=extra,
        mismatches=mismatches,
    )


def verify_outer_archive() -> dict[str, Any]:
    archive = RELEASE_ROOT / "JavaMathProofMesh-0.8.0.zip"
    checksum_path = RELEASE_ROOT / "SHA256SUMS.txt"
    if not is_file(archive) or not is_file(checksum_path):
        return gate(
            False,
            archive_exists=is_file(archive),
            checksum_exists=is_file(checksum_path),
        )
    expected, malformed = parse_checksum_file(checksum_path)
    actual = sha256(archive)
    recorded = expected.get(archive.name)
    return gate(
        not malformed and len(expected) == 1 and recorded == actual,
        path=relative(archive),
        sha256=actual,
        recorded_sha256=recorded,
        malformed_lines=malformed,
    )


def verify_release_contents() -> dict[str, Any]:
    required_files = [
        "lib/mathproofmesh-server-0.8.0-exec.jar",
        "lib/mathproofmesh-server-0.8.0-cli.jar",
        "bin/mathproofmesh.cmd",
        "bin/mathproofmesh-server.cmd",
        "bin/mathproofmesh-sidecar.cmd",
        "bin/mathproofmesh",
        "bin/mathproofmesh-server",
        "bin/mathproofmesh-sidecar",
        "sidecar/requirements.lock",
        "sidecar/build-requirements.lock",
        "compose/compose.yaml",
        "compose/temporal-dev.yaml",
        "compose/image-lock.env",
        "release-manifest.json",
        "README.md",
        "MIGRATION_COMPLETION_REPORT.md",
    ]
    required_docs = [
        "docs/operations.md",
        "docs/providers.md",
        "docs/security.md",
        "docs/temporal.md",
        "docs/compatibility.md",
        "docs/testing.md",
    ]
    missing = [
        item
        for item in required_files + required_docs
        if not is_file(BUNDLE / item)
    ]
    migrations = sorted(path.name for path in (BUNDLE / "db").glob("*.sql"))
    desktop_files = sorted(
        path.name for path in (BUNDLE / "desktop").glob("*") if is_file(path)
    )
    desktop_zip = any(name.lower().endswith(".zip") for name in desktop_files)
    desktop_installer = any(
        name.lower().endswith((".exe", ".msi")) for name in desktop_files
    )
    manifest: dict[str, Any] = {}
    manifest_error = ""
    try:
        manifest = load_json(BUNDLE / "release-manifest.json")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        manifest_error = str(error)
    valid_manifest = (
        manifest.get("product") == "JavaMathProofMesh"
        and manifest.get("version") == "0.8.0"
        and str(manifest.get("java")) == "25"
        and manifest.get("database_migrations") == len(migrations)
        and len(migrations) >= 4
    )
    return gate(
        is_dir(BUNDLE)
        and not missing
        and len(migrations) >= 4
        and desktop_zip
        and desktop_installer
        and valid_manifest,
        missing=missing,
        database_migrations=migrations,
        desktop_artifacts=desktop_files,
        desktop_zip=desktop_zip,
        desktop_installer=desktop_installer,
        release_manifest_valid=valid_manifest,
        release_manifest_error=manifest_error,
    )


def verify_required_docs() -> dict[str, Any]:
    completion = ROOT / "MIGRATION_COMPLETION_REPORT.md"
    phase_report = ROOT / "migration" / "reports" / "phase-17.md"
    missing = [
        relative(path)
        for path in (completion, phase_report)
        if not is_file(path)
    ]
    checks: dict[str, bool] = {}
    if is_file(completion):
        text = read_text(completion, errors="replace").lower()
        checks = {
            "feature_matrix": "feature matrix" in text or "功能矩阵" in text,
            "three_mapping_classes": all(
                item in text
                for item in ("source-state.csv", "test-state.csv", "auxiliary-state.csv")
            ),
            "401_closure": "401" in text,
            "known_limits": "known limit" in text or "已知限制" in text,
            "differences": "difference" in text or "差异" in text,
            "evidence": "evidence" in text or "证据" in text,
        }
    return gate(not missing and checks and all(checks.values()), missing=missing, checks=checks)


def verify_readme_start() -> dict[str, Any]:
    readme = ROOT / "README.md"
    text = read_text(readme, errors="replace")
    checks = {
        "release_command": "package-release.ps1" in text,
        "packaged_cli": "mathproofmesh.cmd" in text,
        "jdk_25": re.search(r"JDK\s+25|Java\s+25", text, re.IGNORECASE) is not None,
        "mock": re.search(r"\bMock\b", text) is not None,
    }
    return gate(all(checks.values()), checks=checks)


def verify_no_incomplete_code() -> dict[str, Any]:
    roots = [
        ROOT / "mathproofmesh-contracts" / "src",
        ROOT / "mathproofmesh-core" / "src",
        ROOT / "mathproofmesh-server" / "src",
        ROOT / "mathproofmesh-desktop" / "src",
        ROOT / "mathproofmesh-compatibility" / "src",
        ROOT / "python-compute-service",
    ]
    extensions = {".java", ".py", ".fxml", ".css"}
    marker = re.compile(
        r"(?im)(?://|#|/\*|\*)\s*(?:TODO|FIXME)\b|"
        r"@(?:Disabled|Ignore)\b|"
        r"\bNOT_IMPLEMENTED\b|"
        r"(?:throw\s+new|new)\s+UnsupportedOperationException\b"
    )
    findings: list[dict[str, Any]] = []
    files_scanned = 0
    for source_root in roots:
        if not is_dir(source_root):
            continue
        for path in source_root.rglob("*"):
            if not is_file(path) or path.suffix.lower() not in extensions:
                continue
            files_scanned += 1
            text = read_text(path, errors="replace")
            for line_number, line in enumerate(text.splitlines(), start=1):
                if marker.search(line):
                    findings.append(
                        {
                            "path": relative(path),
                            "line": line_number,
                            "text": line.strip()[:240],
                        }
                    )
    return gate(not findings, files_scanned=files_scanned, findings=findings)


def verify_release_version() -> dict[str, Any]:
    poms = [ROOT / "pom.xml", *sorted(ROOT.glob("mathproofmesh-*/pom.xml"))]
    snapshot_occurrences: list[str] = []
    versions: list[dict[str, str]] = []
    for pom in poms:
        text = read_text(pom)
        if "SNAPSHOT" in text.upper():
            snapshot_occurrences.append(relative(pom))
        try:
            tree = ET.fromstring(text)
            namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
            version = tree.findtext("m:version", namespaces=namespace)
            if version is None:
                version = tree.findtext("m:parent/m:version", namespaces=namespace)
        except ET.ParseError:
            version = None
        versions.append({"path": relative(pom), "version": version or ""})
    valid_versions = all(item["version"] == "0.8.0" for item in versions)
    return gate(
        not snapshot_occurrences and valid_versions,
        poms=len(poms),
        versions=versions,
        snapshot_occurrences=snapshot_occurrences,
    )


def verify_state() -> dict[str, Any]:
    path = ROOT / "migration" / "state.json"
    state = load_json(path)
    phase = state.get("phases", {}).get("17", {})
    valid = (
        str(state.get("current_phase")) == "17"
        and phase.get("status") == "passed"
        and phase.get("result") == "PASS"
        and phase.get("report") == "migration/reports/phase-17.md"
    )
    return gate(
        valid,
        current_phase=state.get("current_phase"),
        phase_17_status=phase.get("status"),
        phase_17_result=phase.get("result"),
        phase_17_report=phase.get("report"),
    )


def main() -> int:
    failures: list[str] = []
    gates: dict[str, Any] = {}

    authority_zip_exists = is_file(AUTHORITY_ZIP)
    authority_zip_hash = sha256(AUTHORITY_ZIP) if authority_zip_exists else None
    manifest_exists = is_file(SOURCE_MANIFEST)
    manifest_hash = sha256(SOURCE_MANIFEST) if manifest_exists else None
    gates["authority"] = gate(
        authority_zip_hash == EXPECTED_ZIP_SHA256
        and manifest_hash == EXPECTED_MANIFEST_SHA256,
        zip=relative(AUTHORITY_ZIP),
        zip_sha256=authority_zip_hash,
        expected_zip_sha256=EXPECTED_ZIP_SHA256,
        frozen_manifest=relative(SOURCE_MANIFEST),
        frozen_manifest_sha256=manifest_hash,
        expected_frozen_manifest_sha256=EXPECTED_MANIFEST_SHA256,
    )

    source_gate, source_paths = read_map(
        ROOT / "migration" / "source-state.csv",
        "source_file",
        142,
        {"migrated"},
    )
    test_gate, test_paths = read_map(
        ROOT / "migration" / "test-state.csv",
        "python_test_file",
        167,
        {"ported", "differential"},
    )
    auxiliary_gate, auxiliary_paths = read_map(
        ROOT / "migration" / "auxiliary-state.csv",
        "source_file",
        92,
        {
            "translated_verified",
            "reimplemented_verified",
            "copied_verified",
            "verified",
        },
    )
    gates["source_mapping"] = source_gate
    gates["test_mapping"] = test_gate
    gates["auxiliary_mapping"] = auxiliary_gate
    manifest_paths = read_manifest_paths() if manifest_exists else set()
    union = source_paths | test_paths | auxiliary_paths
    overlap_count = (
        len(source_paths)
        + len(test_paths)
        + len(auxiliary_paths)
        - len(union)
    )
    gates["frozen_401_path_closure"] = gate(
        len(manifest_paths) == 401
        and len(union) == 401
        and overlap_count == 0
        and union == manifest_paths,
        frozen_paths=len(manifest_paths),
        mapped_unique_paths=len(union),
        cross_map_overlap_count=overlap_count,
        missing_from_mappings=sorted(manifest_paths - union),
        extra_in_mappings=sorted(union - manifest_paths),
    )

    required_reports = {
        "coverage": "phase-17-coverage.json",
        "security": "phase-17-security.json",
        "licenses": "phase-17-licenses.json",
        "performance": "phase-17-performance.json",
        "python_baseline": "phase-17-python-baseline.json",
        "demonstrations": "phase-17-demonstrations.json",
    }
    gates["reports"] = {
        name: report_gate(ROOT / "migration" / "reports" / filename)
        for name, filename in required_reports.items()
    }
    gates["reports"]["result"] = (
        "PASS"
        if all(item["result"] == "PASS" for item in gates["reports"].values())
        else "FAIL"
    )

    dependency_tree = ROOT / "migration" / "reports" / "phase-17-dependency-tree.txt"
    dependency_text = (
        read_text(dependency_tree, errors="replace")
        if is_file(dependency_tree)
        else ""
    )
    gates["dependency_tree"] = gate(
        is_file(dependency_tree) and "result=PASS" in dependency_text,
        path=relative(dependency_tree),
        sha256=sha256(dependency_tree) if is_file(dependency_tree) else None,
    )
    gates["online_verify"] = verify_log(
        ROOT / "migration" / "reports" / "phase-17-verify-online.log"
    )
    gates["offline_verify"] = verify_log(
        ROOT / "migration" / "reports" / "phase-17-verify-offline.log"
    )
    gates["maven_test_reports"] = verify_test_reports()
    gates["release_contents"] = verify_release_contents()
    gates["bundle_checksums"] = verify_bundle_checksums()
    gates["release_archive"] = verify_outer_archive()
    gates["completion_documents"] = verify_required_docs()
    gates["readme_clean_start"] = verify_readme_start()
    gates["no_required_incomplete_code"] = verify_no_incomplete_code()
    gates["release_version"] = verify_release_version()
    gates["migration_state"] = verify_state()

    for name, value in gates.items():
        if value.get("result") != "PASS":
            failures.append(name)

    payload = {
        "schema_version": "1.0",
        "phase": "17",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "result": "PASS" if not failures else "FAIL",
        "gates": gates,
        "failures": failures,
    }
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    with open(native_path(REPORT), "w", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(payload, indent=2) + "\n")
    print(
        "PHASE 17 COMPLETION GATES: "
        f"{payload['result']} ({len(gates) - len(failures)}/{len(gates)})"
    )
    if failures:
        print("Failures: " + ", ".join(failures))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
