from pathlib import Path

from PyInstaller.utils.hooks import (
    collect_dynamic_libs,
    collect_submodules,
    copy_metadata,
)


project_root = Path(SPECPATH).resolve().parents[1]
source_root = project_root / "src"
icon_path = project_root / "packaging" / "windows" / "assets" / "mathproofmesh.ico"

datas = [
    (
        str(source_root / "mathproofmesh" / "desktop" / "web"),
        "mathproofmesh/desktop/web",
    ),
    (str(project_root / "config.deepseek-v4-pro.smoke.yaml"), "."),
    (str(project_root / "config.deepseek-v4-pro.yaml"), "."),
    (str(project_root / "config.deepseek-v4-pro.topology-active.yaml"), "."),
    (str(project_root / "config.deepseek-v4-pro.proof-control-shadow.yaml"), "."),
    (str(project_root / "config.deepseek-v4-pro.proof-control-active.yaml"), "."),
    (str(project_root / "benchmarks" / "analogy_library.jsonl"), "benchmarks"),
    (str(project_root / "LICENSE"), "."),
]
for distribution in (
    "certifi",
    "networkx",
    "pydantic",
    "pywebview",
    "sympy",
    "z3-solver",
):
    try:
        datas += copy_metadata(distribution)
    except Exception:
        pass

hiddenimports = collect_submodules("uvicorn")
hiddenimports += [
    "clr",
    "webview.platforms.edgechromium",
]
binaries = collect_dynamic_libs("z3")

a = Analysis(
    [str(project_root / "packaging" / "windows" / "desktop_entry.py")],
    pathex=[str(source_root)],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        "IPython",
        "PyQt5",
        "PyQt6",
        "PySide2",
        "PySide6",
        "cefpython3",
        "matplotlib",
        "notebook",
        "pytest",
        "tkinter",
    ],
    noarchive=False,
    optimize=1,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="MathProofMesh",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(icon_path),
    version=str(project_root / "packaging" / "windows" / "version_info.txt"),
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="MathProofMesh",
)
