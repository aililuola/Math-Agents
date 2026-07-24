from __future__ import annotations

import base64
import ctypes
import json
import logging
import os
import tempfile
from ctypes import wintypes
from pathlib import Path
from typing import Protocol


LOGGER = logging.getLogger(__name__)
_ALLOWED_KEYS = {f"DEEPSEEK_AGENT_{index}_KEY" for index in range(1, 6)}


class SecretProtector(Protocol):
    def protect(self, value: bytes) -> bytes: ...

    def unprotect(self, value: bytes) -> bytes: ...


class _DataBlob(ctypes.Structure):
    _fields_ = [
        ("cbData", wintypes.DWORD),
        ("pbData", ctypes.POINTER(ctypes.c_ubyte)),
    ]


class WindowsDpapiProtector:
    """Encrypt secrets for the current Windows user with DPAPI."""

    _entropy = b"MathProofMesh Desktop credentials v1"
    _cryptprotect_ui_forbidden = 0x1

    def __init__(self) -> None:
        if os.name != "nt":
            raise RuntimeError("Windows DPAPI is available only on Windows")
        self._crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
        self._kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

        self._crypt32.CryptProtectData.argtypes = [
            ctypes.POINTER(_DataBlob),
            wintypes.LPCWSTR,
            ctypes.POINTER(_DataBlob),
            wintypes.LPVOID,
            wintypes.LPVOID,
            wintypes.DWORD,
            ctypes.POINTER(_DataBlob),
        ]
        self._crypt32.CryptProtectData.restype = wintypes.BOOL
        self._crypt32.CryptUnprotectData.argtypes = [
            ctypes.POINTER(_DataBlob),
            wintypes.LPVOID,
            ctypes.POINTER(_DataBlob),
            wintypes.LPVOID,
            wintypes.LPVOID,
            wintypes.DWORD,
            ctypes.POINTER(_DataBlob),
        ]
        self._crypt32.CryptUnprotectData.restype = wintypes.BOOL
        self._kernel32.LocalFree.argtypes = [wintypes.HLOCAL]
        self._kernel32.LocalFree.restype = wintypes.HLOCAL

    @staticmethod
    def _blob(value: bytes) -> tuple[_DataBlob, ctypes.Array[ctypes.c_char]]:
        buffer = ctypes.create_string_buffer(value, max(1, len(value)))
        blob = _DataBlob(
            len(value),
            ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)),
        )
        return blob, buffer

    def protect(self, value: bytes) -> bytes:
        return self._transform(value, protect=True)

    def unprotect(self, value: bytes) -> bytes:
        return self._transform(value, protect=False)

    def _transform(self, value: bytes, *, protect: bool) -> bytes:
        input_blob, input_buffer = self._blob(value)
        entropy_blob, entropy_buffer = self._blob(self._entropy)
        output_blob = _DataBlob()
        try:
            if protect:
                ok = self._crypt32.CryptProtectData(
                    ctypes.byref(input_blob),
                    "MathProofMesh API credentials",
                    ctypes.byref(entropy_blob),
                    None,
                    None,
                    self._cryptprotect_ui_forbidden,
                    ctypes.byref(output_blob),
                )
            else:
                ok = self._crypt32.CryptUnprotectData(
                    ctypes.byref(input_blob),
                    None,
                    ctypes.byref(entropy_blob),
                    None,
                    None,
                    self._cryptprotect_ui_forbidden,
                    ctypes.byref(output_blob),
                )
            if not ok:
                error = ctypes.get_last_error()
                raise OSError(error, ctypes.FormatError(error))
            return ctypes.string_at(output_blob.pbData, output_blob.cbData)
        finally:
            if output_blob.pbData:
                self._kernel32.LocalFree(
                    ctypes.cast(output_blob.pbData, wintypes.HLOCAL)
                )
            ctypes.memset(input_buffer, 0, ctypes.sizeof(input_buffer))
            ctypes.memset(entropy_buffer, 0, ctypes.sizeof(entropy_buffer))


class CredentialVault:
    """Keep API keys in memory and optionally persist them with Windows DPAPI."""

    def __init__(
        self,
        path: str | Path,
        *,
        protector: SecretProtector | None = None,
    ) -> None:
        self.path = Path(path)
        self._session: dict[str, str] = {}
        self._protector = protector
        if self._protector is None and os.name == "nt":
            self._protector = WindowsDpapiProtector()

    def set(self, name: str, value: str, *, persist: bool) -> None:
        self._validate_name(name)
        normalized = value.strip()
        if not normalized:
            raise ValueError("API key must not be blank")
        self._session[name] = normalized
        if persist:
            if self._protector is None:
                raise RuntimeError("Persistent credential protection is unavailable")
            payload = self._load_payload()
            encrypted = self._protector.protect(normalized.encode("utf-8"))
            payload[name] = base64.b64encode(encrypted).decode("ascii")
            self._write_payload(payload)

    def get(self, name: str) -> str | None:
        self._validate_name(name)
        if name in self._session:
            return self._session[name]
        payload = self._load_payload()
        encoded = payload.get(name)
        if encoded and self._protector is not None:
            try:
                encrypted = base64.b64decode(encoded, validate=True)
                value = self._protector.unprotect(encrypted).decode("utf-8")
                self._session[name] = value
                return value
            except (OSError, ValueError, UnicodeError) as exc:
                LOGGER.warning("Unable to decrypt stored credential %s: %s", name, exc)
        return os.getenv(name) or None

    def clear(self, name: str) -> None:
        self._validate_name(name)
        self._session.pop(name, None)
        payload = self._load_payload()
        if name in payload:
            del payload[name]
            self._write_payload(payload)

    def clear_all(self) -> None:
        self._session.clear()
        if self.path.exists():
            self.path.unlink()

    def statuses(self) -> dict[str, str]:
        payload = self._load_payload()
        result: dict[str, str] = {}
        for name in sorted(_ALLOWED_KEYS):
            if name in self._session:
                result[name] = "session"
            elif name in payload:
                result[name] = "saved"
            elif os.getenv(name):
                result[name] = "environment"
            else:
                result[name] = "missing"
        return result

    @staticmethod
    def _validate_name(name: str) -> None:
        if name not in _ALLOWED_KEYS:
            raise ValueError(f"Unsupported credential name: {name}")

    def _load_payload(self) -> dict[str, str]:
        if not self.path.exists():
            return {}
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            LOGGER.warning("Ignoring unreadable credential vault: %s", exc)
            return {}
        items = raw.get("items") if isinstance(raw, dict) else None
        if not isinstance(items, dict):
            return {}
        return {
            str(name): str(value)
            for name, value in items.items()
            if name in _ALLOWED_KEYS and isinstance(value, str)
        }

    def _write_payload(self, items: dict[str, str]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        content = json.dumps(
            {"version": 1, "items": items},
            ensure_ascii=True,
            indent=2,
            sort_keys=True,
        )
        fd, temp_name = tempfile.mkstemp(
            prefix=f".{self.path.name}.", dir=str(self.path.parent)
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(content)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_name, self.path)
        finally:
            if os.path.exists(temp_name):
                os.unlink(temp_name)
