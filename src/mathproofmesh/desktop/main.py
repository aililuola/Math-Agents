from __future__ import annotations

import argparse
import logging
import os
import secrets
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path
from urllib.parse import quote

from .. import __version__
from .app import create_desktop_app
from .paths import DesktopPaths, web_root
from .runtime import LocalDesktopServer, SingleInstance


LOGGER = logging.getLogger(__name__)


def configure_logging(log_file: Path) -> None:
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
    file_handler = RotatingFileHandler(
        log_file,
        maxBytes=5 * 1024 * 1024,
        backupCount=4,
        encoding="utf-8",
    )
    file_handler.setFormatter(formatter)
    root.addHandler(file_handler)
    if sys.stderr is not None:
        stream_handler = logging.StreamHandler()
        stream_handler.setFormatter(formatter)
        root.addHandler(stream_handler)


def show_error(title: str, message: str) -> None:
    if os.name == "nt":
        import ctypes

        ctypes.windll.user32.MessageBoxW(None, message, title, 0x10)
    elif sys.stderr is not None:
        print(f"{title}: {message}", file=sys.stderr)


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument(
        "--health-check",
        action="store_true",
        help="Validate packaged desktop resources without opening a window.",
    )
    parser.add_argument(
        "--window-smoke-test",
        action="store_true",
        help="Open the desktop window briefly, then close it automatically.",
    )
    parser.add_argument("--version", action="store_true")
    return parser.parse_args(argv)


def run(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    if args.version:
        if sys.stdout is not None:
            print(__version__)
        return 0
    paths = DesktopPaths.discover()
    configure_logging(paths.log_file)
    if args.health_check:
        app = create_desktop_app(paths=paths, token="health-check")
        web_root()
        snapshot = app.state.desktop_manager.bootstrap()
        if snapshot.get("version") != __version__:
            raise RuntimeError("Desktop version metadata is inconsistent")
        LOGGER.info("Desktop health check passed for version %s", __version__)
        return 0

    instance_name = (
        "Local\\MathProofMeshDesktopWindowSmoke"
        if args.window_smoke_test
        else "Local\\MathProofMeshDesktop"
    )
    with SingleInstance(instance_name) as instance:
        if not instance.acquire():
            show_error("MathProofMesh", "MathProofMesh 已经在运行。")
            return 0

        token = secrets.token_urlsafe(32)
        app = create_desktop_app(paths=paths, token=token)
        server = LocalDesktopServer(app)
        try:
            port = server.start()
            import webview

            url = f"http://127.0.0.1:{port}/?token={quote(token)}"
            window = webview.create_window(
                "MathProofMesh",
                url,
                width=1440,
                height=920,
                min_size=(980, 680),
                background_color="#f5f7f8",
                text_select=True,
                hidden=args.window_smoke_test,
            )
            if args.window_smoke_test:
                window.events.loaded += window.destroy
            webview.start(
                gui="edgechromium",
                debug=os.getenv("MATHPROOFMESH_DESKTOP_DEBUG") == "1",
                private_mode=True,
                storage_path=str(paths.config / "webview"),
            )
            LOGGER.info("Desktop window closed")
            return 0
        except Exception as exc:
            LOGGER.exception("MathProofMesh desktop startup failed")
            show_error(
                "MathProofMesh 启动失败",
                f"{type(exc).__name__}: {exc}\n\n日志：{paths.log_file}",
            )
            return 1
        finally:
            server.stop()


if __name__ == "__main__":
    raise SystemExit(run())
