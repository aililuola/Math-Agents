#!/usr/bin/env python3
"""Enforce phase-17 security, secret, SBOM, and license gates."""

from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
REPORT_ROOT = ROOT / "migration" / "reports"
SECURITY_REPORT = REPORT_ROOT / "phase-17-security.json"
LICENSE_REPORT = REPORT_ROOT / "phase-17-licenses.json"
SBOM = REPORT_ROOT / "phase-17-sbom.json"
DEPENDENCY_CHECK = (
    REPORT_ROOT / "dependency-check" / "dependency-check-report.json"
)
MODULES = (
    "mathproofmesh-contracts",
    "mathproofmesh-core",
    "mathproofmesh-server",
    "mathproofmesh-desktop",
    "mathproofmesh-compatibility",
)
ACCEPTED_LICENSES = {
    "Apache-2.0",
    "MIT",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "CC0-1.0",
    "EPL-2.0",
    "Eclipse Public License (EPL) 2.0",
    "GPL-2.0-with-classpath-exception",
    "LGPL-2.1-only",
    "LGPL-2.1-or-later",
}
SECURITY_SUITES = {
    "secret_redaction": [
        "io.github.aililuola.mathproofmesh.config.SecretValueSecurityTest",
    ],
    "ssrf_and_endpoint_allowlist": [
        "io.github.aililuola.mathproofmesh.config.ProviderEndpointPolicyTest",
    ],
    "sql_and_repository_boundaries": [
        "io.github.aililuola.mathproofmesh.persistence.PersistencePolicyTest",
        "io.github.aililuola.mathproofmesh.persistence.PersistencePostgresIT",
    ],
    "sidecar_injection_timeout_and_output_bounds": [
        "io.github.aililuola.mathproofmesh.sidecar.PythonSidecarProtocolTest",
        "io.github.aililuola.mathproofmesh.sidecar.SandboxSecurityIT",
    ],
    "http_input_auth_concurrency_and_sse_resume": [
        "io.github.aililuola.mathproofmesh.api.ApiGateTest",
    ],
    "provider_sse_resource_bounds": [
        "io.github.aililuola.mathproofmesh.provider.BoundedSseParserTest",
        "io.github.aililuola.mathproofmesh.provider.AgentRuntimePolicyTest",
    ],
}
SECRET_PATTERNS = {
    "private_key": re.compile(
        r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
    ),
    "aws_access_key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "github_token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
    "openai_style_key": re.compile(r"\bsk-[A-Za-z0-9]{32,}\b"),
    "google_api_key": re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    "slack_token": re.compile(r"\bxox[baprs]-[0-9A-Za-z-]{20,}\b"),
    "long_bearer_token": re.compile(r"\bBearer [A-Za-z0-9._~-]{40,}\b"),
}
TEXT_SUFFIXES = {
    ".cmd",
    ".env",
    ".example",
    ".java",
    ".json",
    ".md",
    ".properties",
    ".ps1",
    ".py",
    ".sh",
    ".sql",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}


def filesystem_path(path: Path) -> Path:
    name = str(path)
    if os.name == "nt" and not name.startswith("\\\\?\\"):
        return Path("\\\\?\\" + name)
    return path


def read_json(path: Path) -> dict[str, object]:
    if not filesystem_path(path).is_file():
        raise FileNotFoundError(f"missing report: {path.relative_to(ROOT)}")
    return json.loads(filesystem_path(path).read_text(encoding="utf-8"))


def vulnerability_score(vulnerability: dict[str, object]) -> float:
    scores = []
    for key in ("cvssv4", "cvssv3", "cvssv2"):
        item = vulnerability.get(key)
        if isinstance(item, dict):
            score = item.get("baseScore")
            if isinstance(score, (int, float)):
                scores.append(float(score))
    return max(scores, default=0.0)


def dependency_check_gate() -> dict[str, object]:
    report = read_json(DEPENDENCY_CHECK)
    findings = []
    high = []
    dependencies = report.get("dependencies", [])
    if not isinstance(dependencies, list):
        raise ValueError("Dependency-Check dependencies are malformed")
    for dependency in dependencies:
        if not isinstance(dependency, dict):
            continue
        for vulnerability in dependency.get("vulnerabilities", []) or []:
            if not isinstance(vulnerability, dict):
                continue
            item = {
                "dependency": dependency.get("fileName", ""),
                "cve": vulnerability.get("name", ""),
                "severity": vulnerability.get("severity", ""),
                "cvss": vulnerability_score(vulnerability),
            }
            findings.append(item)
            if item["cvss"] >= 7.0:
                high.append(item)
    return {
        "report": DEPENDENCY_CHECK.relative_to(ROOT).as_posix(),
        "dependencies_scanned": len(dependencies),
        "visible_findings": findings,
        "cvss_ge_7_findings": high,
        "threshold": 7.0,
        "result": "PASS" if not high else "FAIL",
    }


def spotbugs_gate() -> dict[str, object]:
    modules = {}
    passed = True
    for module in MODULES:
        path = ROOT / "target" / "modules" / module / "spotbugs.xml"
        if not filesystem_path(path).is_file():
            modules[module] = {"result": "MISSING"}
            passed = False
            continue
        root = ET.parse(filesystem_path(path)).getroot()
        bugs = root.findall(".//BugInstance")
        security = [
            bug
            for bug in bugs
            if "SECURITY" in bug.get("category", "").upper()
            or any(
                token in bug.get("type", "").upper()
                for token in (
                    "INJECTION",
                    "PATH_TRAVERSAL",
                    "CIPHER",
                    "TRUST",
                    "XXE",
                    "DESERIALIZATION",
                    "REDOS",
                )
            )
        ]
        module_passed = not bugs
        passed &= module_passed
        modules[module] = {
            "bugs": len(bugs),
            "security_bugs": len(security),
            "result": "PASS" if module_passed else "FAIL",
            "report": path.relative_to(ROOT).as_posix(),
        }
    return {
        "analyzers": ["SpotBugs", "FindSecBugs"],
        "modules": modules,
        "result": "PASS" if passed else "FAIL",
    }


def license_gate() -> dict[str, object]:
    sbom = read_json(SBOM)
    components = sbom.get("components", [])
    if not isinstance(components, list):
        raise ValueError("CycloneDX components are malformed")
    inventory = []
    counts: Counter[str] = Counter()
    missing = []
    unreviewed = []
    for component in components:
        if not isinstance(component, dict):
            continue
        licenses = []
        for item in component.get("licenses", []) or []:
            if not isinstance(item, dict):
                continue
            license_data = item.get("license", {})
            if isinstance(license_data, dict):
                name = license_data.get("id") or license_data.get("name")
                if isinstance(name, str) and name:
                    licenses.append(name)
                    counts[name] += 1
        coordinate = ":".join(
            str(component.get(key, ""))
            for key in ("group", "name", "version")
        )
        if not licenses:
            missing.append(coordinate)
        if any(name not in ACCEPTED_LICENSES for name in licenses):
            unreviewed.append({"component": coordinate, "licenses": licenses})
        inventory.append(
            {
                "component": coordinate,
                "purl": component.get("purl", ""),
                "licenses": licenses,
            }
        )
    result = "PASS" if not missing and not unreviewed else "FAIL"
    payload = {
        "schema_version": "1.0",
        "phase": "17",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "result": result,
        "sbom": SBOM.relative_to(ROOT).as_posix(),
        "component_count": len(inventory),
        "accepted_license_policy": sorted(ACCEPTED_LICENSES),
        "license_counts": dict(sorted(counts.items())),
        "copyleft_review": (
            "EPL/LGPL and GPL-with-Classpath-Exception dependencies are accepted "
            "as unmodified runtime or tooling libraries; source and license notices "
            "remain discoverable through the CycloneDX component inventory."
        ),
        "missing_license_components": missing,
        "unreviewed_license_components": unreviewed,
        "components": inventory,
    }
    LICENSE_REPORT.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return {
        "report": LICENSE_REPORT.relative_to(ROOT).as_posix(),
        "components": len(inventory),
        "missing": len(missing),
        "unreviewed": len(unreviewed),
        "result": result,
    }


def active_text_files() -> list[Path]:
    roots = [
        ROOT / "pom.xml",
        ROOT / ".env.local.example",
        ROOT / "mathproofmesh-contracts",
        ROOT / "mathproofmesh-core",
        ROOT / "mathproofmesh-server",
        ROOT / "mathproofmesh-desktop",
        ROOT / "mathproofmesh-compatibility",
        ROOT / "python-compute-service",
        ROOT / "scripts",
        ROOT / "config",
        ROOT / "compose",
        ROOT / "docs",
        ROOT / "ci",
    ]
    files = []
    for item in roots:
        if filesystem_path(item).is_file():
            files.append(item)
            continue
        top = str(filesystem_path(item))
        for directory, names, filenames in os.walk(top):
            names[:] = [
                name
                for name in names
                if name not in {"target", "__pycache__", ".cache", ".tools"}
            ]
            for filename in filenames:
                path_name = str(Path(directory) / filename)
                if path_name.startswith("\\\\?\\"):
                    path_name = path_name[4:]
                path = Path(path_name)
                if path.suffix.lower() in TEXT_SUFFIXES:
                    files.append(path)
    return sorted(set(files), key=lambda path: str(path).lower())


def secret_gate() -> dict[str, object]:
    findings = []
    files = active_text_files()
    for path in files:
        text = filesystem_path(path).read_text(encoding="utf-8", errors="replace")
        for label, pattern in SECRET_PATTERNS.items():
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                findings.append(
                    {
                        "rule": label,
                        "file": path.relative_to(ROOT).as_posix(),
                        "line": line,
                    }
                )
    return {
        "policy": (
            "High-confidence private-key and provider-token formats are scanned "
            "across active source, tests, scripts, configuration, CI, and docs. "
            "Values are never copied into the report."
        ),
        "files_scanned": len(files),
        "findings": findings,
        "result": "PASS" if not findings else "FAIL",
    }


def find_suite(fqcn: str) -> Path | None:
    filename = f"TEST-{fqcn}.xml"
    for module in MODULES:
        for report_kind in ("surefire-reports", "failsafe-reports"):
            path = ROOT / "target" / "modules" / module / report_kind / filename
            if filesystem_path(path).is_file():
                return path
    return None


def security_test_gate() -> dict[str, object]:
    surfaces = {}
    all_passed = True
    for surface, suites in SECURITY_SUITES.items():
        results = []
        surface_passed = True
        for suite in suites:
            path = find_suite(suite)
            if path is None:
                results.append({"suite": suite, "result": "MISSING"})
                surface_passed = False
                continue
            root = ET.parse(filesystem_path(path)).getroot()
            counts = {
                key: int(root.get(key, "0"))
                for key in ("tests", "failures", "errors", "skipped")
            }
            passed = (
                counts["tests"] > 0
                and counts["failures"] == 0
                and counts["errors"] == 0
                and counts["skipped"] == 0
            )
            surface_passed &= passed
            results.append(
                {
                    "suite": suite,
                    **counts,
                    "result": "PASS" if passed else "FAIL",
                    "report": path.relative_to(ROOT).as_posix(),
                }
            )
        all_passed &= surface_passed
        surfaces[surface] = {
            "result": "PASS" if surface_passed else "FAIL",
            "suites": results,
        }
    return {
        "policy": (
            "Injection, traversal, SSRF, secret, parser, process, concurrency, "
            "timeout, output, and persistence boundaries require executed suites "
            "with zero failure, error, or skip."
        ),
        "surfaces": surfaces,
        "result": "PASS" if all_passed else "FAIL",
    }


def suppression_gate() -> dict[str, object]:
    path = ROOT / "migration" / "dependency-check-suppressions.xml"
    root = ET.parse(filesystem_path(path)).getroot()
    now = datetime.now(timezone.utc)
    items = []
    passed = True
    for suppress in list(root):
        until = suppress.get("until", "")
        notes_element = next(
            (child for child in list(suppress) if child.tag.endswith("notes")),
            None,
        )
        notes = "" if notes_element is None else "".join(notes_element.itertext())
        try:
            if until.endswith("Z"):
                expiry = datetime.fromisoformat(until[:-1]).replace(
                    tzinfo=timezone.utc
                )
            else:
                expiry = datetime.fromisoformat(until)
                if expiry.tzinfo is None:
                    expiry = expiry.replace(tzinfo=timezone.utc)
            not_expired = expiry > now
        except ValueError:
            not_expired = False
        complete = (
            not_expired
            and "Owner:" in notes
            and "Scope:" in notes
            and ("CVE-" in notes or "CVEs: N/A" in notes)
            and ("Review" in notes or "review" in notes)
        )
        passed &= complete
        items.append(
            {
                "until": until,
                "owner_recorded": "Owner:" in notes,
                "scope_and_reason_recorded": "Scope:" in notes,
                "cve_or_explicit_na_recorded": (
                    "CVE-" in notes or "CVEs: N/A" in notes
                ),
                "review_instruction_recorded": (
                    "Review" in notes or "review" in notes
                ),
                "not_expired": not_expired,
                "result": "PASS" if complete else "FAIL",
            }
        )
    return {
        "suppressions": items,
        "result": "PASS" if passed and items else "FAIL",
    }


def deployment_documentation_gate() -> dict[str, object]:
    security = filesystem_path(ROOT / "docs" / "security.md").read_text(
        encoding="utf-8"
    )
    operations = filesystem_path(ROOT / "docs" / "operations.md").read_text(
        encoding="utf-8"
    )
    temporal = filesystem_path(ROOT / "docs" / "temporal.md").read_text(
        encoding="utf-8"
    )
    compose = filesystem_path(ROOT / "compose.yaml").read_text(encoding="utf-8")
    checks = {
        "loopback_development_binding": "127.0.0.1:" in compose,
        "production_tls_or_mtls": (
            ("TLS" in security or "TLS" in operations)
            and ("mTLS" in security or "mTLS" in operations)
            and "mTLS" in temporal
        ),
        "least_privilege": (
            "least-privilege" in operations or "least privilege" in operations
        ),
        "backup_and_restore": (
            "backup" in operations.lower() and "restore" in operations.lower()
        ),
        "development_not_production": bool(
            re.search(r"not\s+(?:a\s+)?production", operations, re.IGNORECASE)
        ),
    }
    return {
        "checks": {
            name: "PASS" if passed else "FAIL"
            for name, passed in checks.items()
        },
        "result": "PASS" if all(checks.values()) else "FAIL",
    }


def release_version_gate() -> dict[str, object]:
    poms = [ROOT / "pom.xml"] + [ROOT / module / "pom.xml" for module in MODULES]
    snapshots = []
    for pom in poms:
        for number, line in enumerate(
            filesystem_path(pom).read_text(encoding="utf-8").splitlines(), 1
        ):
            if "SNAPSHOT" in line:
                snapshots.append(
                    {"file": pom.relative_to(ROOT).as_posix(), "line": number}
                )
    return {
        "version": "0.8.0",
        "active_poms": len(poms),
        "snapshot_occurrences": snapshots,
        "result": "PASS" if not snapshots else "FAIL",
    }


def main() -> int:
    failures = []
    try:
        gates = {
            "owasp_dependency_check": dependency_check_gate(),
            "spotbugs_findsecbugs": spotbugs_gate(),
            "licenses": license_gate(),
            "secret_scan": secret_gate(),
            "security_tests": security_test_gate(),
            "suppression_metadata": suppression_gate(),
            "deployment_documentation": deployment_documentation_gate(),
            "release_version": release_version_gate(),
        }
        failures = [
            name for name, value in gates.items() if value.get("result") != "PASS"
        ]
        payload = {
            "schema_version": "1.0",
            "phase": "17",
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "result": "PASS" if not failures else "FAIL",
            "gates": gates,
            "failures": failures,
        }
    except (FileNotFoundError, OSError, ValueError, ET.ParseError, json.JSONDecodeError) as error:
        failures = [str(error)]
        payload = {
            "schema_version": "1.0",
            "phase": "17",
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "result": "FAIL",
            "failures": failures,
        }
    SECURITY_REPORT.parent.mkdir(parents=True, exist_ok=True)
    SECURITY_REPORT.write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )
    print(f"PHASE 17 SECURITY: {payload['result']}")
    for failure in failures:
        print(f"  - {failure}", file=sys.stderr)
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
