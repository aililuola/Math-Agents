#!/usr/bin/env python3
"""Remove machine-local artifact prefixes from Dependency-Check reports."""

from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any, Iterator


PROJECT_ROOT = Path(__file__).resolve().parent.parent
REPORT_DIR = PROJECT_ROOT / "migration" / "reports" / "dependency-check"
RAW_DIR = PROJECT_ROOT / "migration" / "logs" / "phase-17-dependency-check-raw"
JSON_REPORT = REPORT_DIR / "dependency-check-report.json"
HTML_REPORT = REPORT_DIR / "dependency-check-report.html"
REPOSITORY_MARKER = "/.cache/maven-repository/"
REPOSITORY_TOKEN = "${MAVEN_REPOSITORY}"
PROJECT_TOKEN = "${PROJECT_ROOT}"


def file_paths(value: Any) -> Iterator[str]:
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "filePath" and isinstance(child, str):
                yield child
            else:
                yield from file_paths(child)
    elif isinstance(value, list):
        for child in value:
            yield from file_paths(child)


def replacement_for(path: str) -> tuple[str, str] | None:
    if path.startswith((REPOSITORY_TOKEN, PROJECT_TOKEN)):
        return None

    normalized = path.replace("\\", "/")
    lower_normalized = normalized.lower()
    marker_index = lower_normalized.find(REPOSITORY_MARKER)
    if marker_index >= 0:
        prefix_length = marker_index + len(REPOSITORY_MARKER)
        separator = "\\" if "\\" in path[:prefix_length] else "/"
        return path[:prefix_length], REPOSITORY_TOKEN + separator

    normalized_root = PROJECT_ROOT.as_posix()
    if lower_normalized.startswith(normalized_root.lower() + "/"):
        prefix_length = len(normalized_root) + 1
        separator = "\\" if "\\" in path[:prefix_length] else "/"
        return path[:prefix_length], PROJECT_TOKEN + separator

    raise ValueError(f"Unexpected Dependency-Check file path: {path}")


def json_escaped(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)[1:-1]


def main() -> None:
    raw_json_bytes = JSON_REPORT.read_bytes()
    raw_html_bytes = HTML_REPORT.read_bytes()
    report = json.loads(raw_json_bytes.decode("utf-8"))

    replacements = {
        replacement
        for path in file_paths(report)
        if (replacement := replacement_for(path)) is not None
    }

    if replacements:
        RAW_DIR.mkdir(parents=True, exist_ok=True)
        shutil.copy2(JSON_REPORT, RAW_DIR / JSON_REPORT.name)
        shutil.copy2(HTML_REPORT, RAW_DIR / HTML_REPORT.name)

    json_text = raw_json_bytes.decode("utf-8")
    html_text = raw_html_bytes.decode("utf-8")
    json_replacements = 0
    html_replacements = 0

    for prefix, token in sorted(replacements, key=lambda item: len(item[0]), reverse=True):
        escaped_prefix = json_escaped(prefix)
        escaped_token = json_escaped(token)
        json_replacements += json_text.count(escaped_prefix)
        html_replacements += html_text.count(prefix)
        json_text = json_text.replace(escaped_prefix, escaped_token)
        html_text = html_text.replace(prefix, token)

    normalized_report = json.loads(json_text)
    unexpected_paths = [
        path
        for path in file_paths(normalized_report)
        if not path.startswith((REPOSITORY_TOKEN, PROJECT_TOKEN))
    ]
    if unexpected_paths:
        raise ValueError(f"Unnormalized Dependency-Check path: {unexpected_paths[0]}")

    JSON_REPORT.write_bytes(json_text.encode("utf-8"))
    HTML_REPORT.write_bytes(html_text.encode("utf-8"))
    print(
        "DEPENDENCY-CHECK PATH NORMALIZATION: PASS "
        f"json={json_replacements} html={html_replacements}"
    )


if __name__ == "__main__":
    main()
