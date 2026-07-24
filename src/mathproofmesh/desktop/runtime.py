from __future__ import annotations

import ctypes
import logging
import os
import socket
import threading
import time
from ctypes import wintypes
from types import TracebackType
from typing import Any

import uvicorn


LOGGER = logging.getLogger(__name__)
_ERROR_ALREADY_EXISTS = 183


class SingleInstance:
    def __init__(self, name: str = "Local\\MathProofMeshDesktop") -> None:
        self.name = name
        self._handle: int | None = None

    def acquire(self) -> bool:
        if os.name != "nt":
            return True
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CreateMutexW.argtypes = [
            wintypes.LPVOID,
            wintypes.BOOL,
            wintypes.LPCWSTR,
        ]
        kernel32.CreateMutexW.restype = wintypes.HANDLE
        handle = kernel32.CreateMutexW(None, False, self.name)
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        self._handle = int(handle)
        return ctypes.get_last_error() != _ERROR_ALREADY_EXISTS

    def close(self) -> None:
        if self._handle is None or os.name != "nt":
            return
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel32.CloseHandle.restype = wintypes.BOOL
        kernel32.CloseHandle(self._handle)
        self._handle = None

    def __enter__(self) -> "SingleInstance":
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()


class LocalDesktopServer:
    def __init__(self, app: Any) -> None:
        self.app = app
        self.server: uvicorn.Server | None = None
        self.thread: threading.Thread | None = None
        self.socket: socket.socket | None = None
        self.port: int | None = None

    def start(self, *, timeout: float = 15.0) -> int:
        if self.thread is not None:
            raise RuntimeError("Desktop server is already running")
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", 0))
        sock.listen(2048)
        self.socket = sock
        self.port = int(sock.getsockname()[1])
        config = uvicorn.Config(
            self.app,
            host="127.0.0.1",
            port=self.port,
            log_config=None,
            access_log=False,
            lifespan="on",
        )
        self.server = uvicorn.Server(config)
        self.thread = threading.Thread(
            target=self._run,
            name="mathproofmesh-desktop-server",
            daemon=True,
        )
        self.thread.start()
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.server.started:
                return self.port
            if not self.thread.is_alive():
                break
            time.sleep(0.05)
        self.stop()
        raise RuntimeError("MathProofMesh desktop server did not start")

    def _run(self) -> None:
        assert self.server is not None
        assert self.socket is not None
        try:
            self.server.run(sockets=[self.socket])
        except Exception:
            LOGGER.exception("Desktop server terminated unexpectedly")

    def stop(self, *, timeout: float = 10.0) -> None:
        server = self.server
        thread = self.thread
        if server is not None:
            server.should_exit = True
        if thread is not None and thread.is_alive():
            thread.join(timeout=timeout)
        if self.socket is not None:
            try:
                self.socket.close()
            except OSError:
                pass
        self.socket = None
        self.thread = None
        self.server = None

    def __enter__(self) -> "LocalDesktopServer":
        self.start()
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.stop()
