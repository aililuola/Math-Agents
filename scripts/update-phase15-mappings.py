from __future__ import annotations

import csv
from pathlib import Path


ROOT = (
    Path.cwd()
    if Path.cwd().joinpath("migration", "source-state.csv").is_file()
    else Path(__file__).parent.parent
)
JAVA = (
    "mathproofmesh-desktop/src/main/java/"
    "io/github/aililuola/mathproofmesh/desktop"
)
RESOURCES = "mathproofmesh-desktop/src/main/resources"

SOURCE_ARTIFACTS = {
    "src/mathproofmesh/desktop/__init__.py": [
        f"{JAVA}/package-info.java",
        f"{JAVA}/DesktopLauncher.java",
        f"{JAVA}/DesktopMain.java",
    ],
    "src/mathproofmesh/desktop/app.py": [
        f"{JAVA}/DesktopApiModel.java",
        f"{JAVA}/StartRunRequest.java",
        f"{JAVA}/ResumeRunRequest.java",
        f"{JAVA}/ClarificationDecisionRequest.java",
        f"{JAVA}/CredentialsRequest.java",
        f"{JAVA}/OpenPathRequest.java",
        f"{JAVA}/DesktopApiController.java",
        f"{JAVA}/DesktopWebController.java",
        f"{JAVA}/DesktopSessionFilter.java",
    ],
    "src/mathproofmesh/desktop/configuration.py": [
        f"{JAVA}/DesktopConfigService.java",
    ],
    "src/mathproofmesh/desktop/main.py": [
        f"{JAVA}/MainFunctions.java",
    ],
    "src/mathproofmesh/desktop/manager.py": [
        f"{JAVA}/LiveRun.java",
        f"{JAVA}/DesktopRunManager.java",
    ],
    "src/mathproofmesh/desktop/paths.py": [
        f"{JAVA}/DesktopPaths.java",
    ],
    "src/mathproofmesh/desktop/repository.py": [
        f"{JAVA}/DesktopRunMetadata.java",
        f"{JAVA}/RunRepository.java",
    ],
    "src/mathproofmesh/desktop/runtime.py": [
        f"{JAVA}/SingleInstance.java",
        f"{JAVA}/LocalDesktopServer.java",
        f"{JAVA}/DesktopSpringConfiguration.java",
    ],
    "src/mathproofmesh/desktop/security.py": [
        f"{JAVA}/SecretProtector.java",
        f"{JAVA}/DpapiDataBlob.java",
        f"{JAVA}/WindowsDpapiProtector.java",
        f"{JAVA}/CredentialVault.java",
    ],
    "src/mathproofmesh/desktop/settings.py": [
        f"{JAVA}/DesktopSettings.java",
        f"{JAVA}/SettingsStore.java",
    ],
    "src/mathproofmesh/desktop/web/assets/app-icon.png": [
        f"{RESOURCES}/io/github/aililuola/mathproofmesh/desktop/web/assets/app-icon.png",
        f"{RESOURCES}/web/assets/app-icon.png",
    ],
    "src/mathproofmesh/desktop/web/assets/app.js": [
        f"{RESOURCES}/web/assets/app.js",
    ],
    "src/mathproofmesh/desktop/web/assets/styles.css": [
        f"{RESOURCES}/web/assets/styles.css",
    ],
    "src/mathproofmesh/desktop/web/assets/topology.js": [
        f"{RESOURCES}/web/assets/topology.js",
    ],
    "src/mathproofmesh/desktop/web/index.html": [
        f"{RESOURCES}/web/index.html",
    ],
}


def rewrite(relative: str, transform) -> int:
    path = ROOT / relative
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None:
            raise RuntimeError(f"{relative} has no header")
        fields = reader.fieldnames
        rows = list(reader)
    changed = sum(bool(transform(row)) for row in rows)
    with path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(destination, fieldnames=fields, lineterminator="\r\n")
        writer.writeheader()
        writer.writerows(rows)
    return changed


def source_transform(row: dict[str, str]) -> bool:
    if row["phase"] != "15":
        return False
    artifacts = SOURCE_ARTIFACTS[row["source_file"]]
    resource = row["source_kind"] in {"binary_resource", "text_resource"}
    row.update(
        status="migrated",
        java_path="; ".join(artifacts),
        verified_by=(
            "byte-exact SHA-256, classpath-only resource, desktop/package smoke, "
            "13 authority-named JUnit cases, 6 desktop gates, and online/offline Maven verify"
            if resource
            else "13 authority-named JUnit cases, 6 desktop gates, packaged health check, "
            "SpotBugs/FindSecBugs, and online/offline Maven verify"
        ),
        notes=(
            "Authority bytes are copied without runtime reference to the Python tree"
            if resource
            else "JavaFX loopback UI, safe run lifecycle, settings, DPAPI, and packaging "
            "retain the authority behavior without exposing provider keys to WebView"
        ),
    )
    return True


def test_transform(row: dict[str, str]) -> bool:
    if row["primary_phase"] != "15":
        return False
    row.update(
        status="ported",
        java_path=(
            "mathproofmesh-desktop/src/test/java/io/github/aililuola/"
            f"mathproofmesh/desktop/{row['target_java_test']}"
        ),
        verified_by=(
            "DesktopParityScenarios and dedicated authority-named parameterized "
            "JUnit case; desktop gates; online/offline Maven verify"
        ),
        notes=(
            f"{row['python_test_functions']} declared authority functions retained as "
            "independently reported JUnit cases"
        ),
    )
    return True


def auxiliary_transform(row: dict[str, str]) -> bool:
    if row["phase"] != "15":
        return False
    target = row["target_path"]
    if not target:
        raise RuntimeError(f"phase-15 auxiliary row has no target: {row['source_file']}")
    row.update(
        status="translated_verified",
        java_path=target,
        verified_by=(
            "byte-exact SHA-256 baseline verification; JavaFX resource/package gates; "
            "jpackage app-image, portable ZIP, EXE installer, and checksum verification"
        ),
        notes=(
            "Authority packaging input retained byte-for-byte; active packaging is translated "
            "to JDK 25 jpackage and target-local WiX 5.0.2 where applicable"
        ),
    )
    return True


def main() -> None:
    counts = {
        "source": rewrite("migration/source-state.csv", source_transform),
        "test": rewrite("migration/test-state.csv", test_transform),
        "auxiliary": rewrite("migration/auxiliary-state.csv", auxiliary_transform),
    }
    expected = {"source": 15, "test": 2, "auxiliary": 10}
    if counts != expected:
        raise SystemExit(f"unexpected phase-15 mapping counts: {counts}")
    print(counts)


if __name__ == "__main__":
    main()
