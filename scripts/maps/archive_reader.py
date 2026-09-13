"""Bounded ZIP inspection and extraction for offline map research."""

from __future__ import annotations

import os
import re
import shutil
import stat
import tempfile
import unicodedata
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import BinaryIO


class ArchiveSafetyError(ValueError):
    """Raised before unsafe or excessive archive content is written."""


@dataclass(frozen=True)
class ArchiveLimits:
    max_members: int = 25_000
    max_member_size: int = 512 * 1024 * 1024
    max_expanded_size: int = 4 * 1024 * 1024 * 1024
    max_compression_ratio: float = 200.0

    def __post_init__(self) -> None:
        if self.max_members < 1:
            raise ValueError("max_members must be positive")
        if self.max_member_size < 1:
            raise ValueError("max_member_size must be positive")
        if self.max_expanded_size < 1:
            raise ValueError("max_expanded_size must be positive")
        if self.max_compression_ratio < 1:
            raise ValueError("max_compression_ratio must be at least 1")


@dataclass(frozen=True)
class _ApprovedMember:
    info: zipfile.ZipInfo
    relative_path: Path
    is_directory: bool


_DRIVE_PATH = re.compile(r"^[A-Za-z]:")
_WINDOWS_DEVICE = re.compile(
    r"^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\..*)?$",
    re.IGNORECASE,
)
_WINDOWS_REPARSE_POINT = 0x0400
_COPY_CHUNK_SIZE = 1024 * 1024


if os.name == "nt":
    import ctypes
    import msvcrt
    from ctypes import wintypes

    _KERNEL32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _CREATE_FILE = _KERNEL32.CreateFileW
    _CREATE_FILE.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        ctypes.c_void_p,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    _CREATE_FILE.restype = wintypes.HANDLE
    _CLOSE_HANDLE = _KERNEL32.CloseHandle
    _CLOSE_HANDLE.argtypes = [wintypes.HANDLE]
    _CLOSE_HANDLE.restype = wintypes.BOOL
    _GET_FINAL_PATH = _KERNEL32.GetFinalPathNameByHandleW
    _GET_FINAL_PATH.argtypes = [wintypes.HANDLE, wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD]
    _GET_FINAL_PATH.restype = wintypes.DWORD
    _INVALID_HANDLE = ctypes.c_void_p(-1).value
    _FILE_SHARE_READ = 0x1
    _FILE_SHARE_WRITE = 0x2
    _OPEN_EXISTING = 3
    _CREATE_NEW = 1
    _GENERIC_WRITE = 0x40000000
    _FILE_ATTRIBUTE_NORMAL = 0x80
    _FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
    _FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000


class _PinnedDirectory:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._handle: int | None = None
        if os.name != "nt":
            _reject_linked_path(path)
            return

        handle = _CREATE_FILE(
            str(path),
            0,
            _FILE_SHARE_READ | _FILE_SHARE_WRITE,
            None,
            _OPEN_EXISTING,
            _FILE_FLAG_BACKUP_SEMANTICS | _FILE_FLAG_OPEN_REPARSE_POINT,
            None,
        )
        if handle == _INVALID_HANDLE:
            raise OSError(ctypes.get_last_error(), f"unable to pin directory: {path}")
        self._handle = handle
        try:
            attributes = path.lstat().st_file_attributes
            if not path.is_dir() or attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT:
                raise ArchiveSafetyError(f"destination contains a link or reparse point: {path}")
            buffer = ctypes.create_unicode_buffer(32768)
            length = _GET_FINAL_PATH(handle, buffer, len(buffer), 0)
            if not length or length >= len(buffer):
                raise OSError(ctypes.get_last_error(), f"unable to resolve pinned directory: {path}")
            actual = buffer.value
            if actual.startswith("\\\\?\\"):
                actual = actual[4:]
            if os.path.normcase(os.path.abspath(actual)) != os.path.normcase(os.path.abspath(path)):
                raise ArchiveSafetyError(f"pinned directory resolved outside its expected path: {path}")
        except Exception:
            self.close()
            raise

    def close(self) -> None:
        if self._handle is not None:
            _CLOSE_HANDLE(self._handle)
            self._handle = None


def _pin_directory_chain(path: Path) -> list[_PinnedDirectory]:
    resolved = Path(os.path.abspath(path))
    chain = list(reversed((resolved, *resolved.parents)))
    pins: list[_PinnedDirectory] = []
    try:
        for directory in chain:
            pins.append(_PinnedDirectory(directory))
        return pins
    except Exception:
        for pin in reversed(pins):
            pin.close()
        raise


def _open_new_leaf(path: Path) -> BinaryIO:
    if os.name != "nt":
        return path.open("xb")
    handle = _CREATE_FILE(
        str(path),
        _GENERIC_WRITE,
        _FILE_SHARE_READ | _FILE_SHARE_WRITE,
        None,
        _CREATE_NEW,
        _FILE_ATTRIBUTE_NORMAL | _FILE_FLAG_OPEN_REPARSE_POINT,
        None,
    )
    if handle == _INVALID_HANDLE:
        raise OSError(ctypes.get_last_error(), f"unable to create extraction leaf safely: {path}")
    try:
        descriptor = msvcrt.open_osfhandle(handle, os.O_WRONLY | os.O_BINARY)
    except Exception:
        _CLOSE_HANDLE(handle)
        raise
    return os.fdopen(descriptor, "wb")


def _has_reparse_attribute(path: Path) -> bool:
    try:
        attributes = path.lstat().st_file_attributes
    except (AttributeError, FileNotFoundError):
        return False
    return bool(attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT)


def _reject_linked_path(path: Path) -> None:
    current = path
    while current != current.parent:
        if current.exists() or current.is_symlink():
            if current.is_symlink() or _has_reparse_attribute(current):
                raise ArchiveSafetyError(f"destination contains a link or reparse point: {current}")
        current = current.parent


def _member_path(info: zipfile.ZipInfo) -> tuple[Path, str]:
    name = unicodedata.normalize("NFC", info.filename.replace("\\", "/"))
    if not name or "\x00" in name:
        raise ArchiveSafetyError("archive member has an empty or invalid path")
    if name.startswith("/") or name.startswith("//"):
        raise ArchiveSafetyError(f"archive member uses a rooted path: {info.filename!r}")
    if _DRIVE_PATH.match(name):
        raise ArchiveSafetyError(f"archive member uses a drive-qualified path: {info.filename!r}")

    pure_path = PurePosixPath(name)
    if not pure_path.parts or any(part in ("", ".", "..") for part in pure_path.parts):
        raise ArchiveSafetyError(f"archive member uses path traversal: {info.filename!r}")
    if any(":" in part for part in pure_path.parts):
        raise ArchiveSafetyError(f"archive member uses a drive or alternate-stream path: {info.filename!r}")
    if any(part.endswith((" ", ".")) or _WINDOWS_DEVICE.match(part) for part in pure_path.parts):
        raise ArchiveSafetyError(f"archive member uses an invalid Windows path: {info.filename!r}")

    relative_path = Path(*pure_path.parts)
    normalized_key = "/".join(pure_path.parts).casefold()
    return relative_path, normalized_key


def _member_kind(info: zipfile.ZipInfo) -> tuple[bool, bool]:
    unix_mode = info.external_attr >> 16
    file_type = stat.S_IFMT(unix_mode)
    is_directory = info.is_dir() or file_type == stat.S_IFDIR
    is_link = file_type == stat.S_IFLNK
    if file_type not in (0, stat.S_IFREG, stat.S_IFDIR, stat.S_IFLNK):
        is_link = True
    if info.create_system == 0 and info.external_attr & _WINDOWS_REPARSE_POINT:
        is_link = True
    return is_directory, is_link


def _approve_members(archive: zipfile.ZipFile, limits: ArchiveLimits) -> list[_ApprovedMember]:
    members = archive.infolist()
    if len(members) > limits.max_members:
        raise ArchiveSafetyError(
            f"archive member count {len(members)} exceeds limit {limits.max_members}"
        )

    approved: list[_ApprovedMember] = []
    seen: dict[str, bool] = {}
    expanded_size = 0
    for info in members:
        relative_path, normalized_key = _member_path(info)
        is_directory, is_link = _member_kind(info)
        if is_link:
            raise ArchiveSafetyError(f"archive member is a link or reparse-like entry: {info.filename!r}")
        if normalized_key in seen:
            raise ArchiveSafetyError(f"archive contains duplicate output path: {info.filename!r}")

        parent_key = normalized_key
        while "/" in parent_key:
            parent_key = parent_key.rsplit("/", 1)[0]
            if parent_key in seen and not seen[parent_key]:
                raise ArchiveSafetyError(f"archive output path has a file parent: {info.filename!r}")
        if not is_directory and any(key.startswith(normalized_key + "/") for key in seen):
            raise ArchiveSafetyError(f"archive output path conflicts with a parent directory: {info.filename!r}")

        if info.flag_bits & 0x1:
            raise ArchiveSafetyError(f"archive member is encrypted: {info.filename!r}")
        if info.file_size > limits.max_member_size:
            raise ArchiveSafetyError(
                f"archive member size {info.file_size} exceeds limit {limits.max_member_size}: {info.filename!r}"
            )
        expanded_size += info.file_size
        if expanded_size > limits.max_expanded_size:
            raise ArchiveSafetyError(
                f"archive expanded size {expanded_size} exceeds limit {limits.max_expanded_size}"
            )
        if not is_directory and info.file_size:
            ratio = info.file_size / max(info.compress_size, 1)
            if ratio > limits.max_compression_ratio:
                raise ArchiveSafetyError(
                    f"archive member compression ratio {ratio:.1f} exceeds limit "
                    f"{limits.max_compression_ratio:.1f}: {info.filename!r}"
                )

        seen[normalized_key] = is_directory
        approved.append(_ApprovedMember(info, relative_path, is_directory))
    return approved


def safe_extract(
    archive: str | os.PathLike[str] | BinaryIO,
    destination: str | os.PathLike[str],
    limits: ArchiveLimits,
) -> list[Path]:
    """Extract a ZIP atomically after validating every member and size budget.

    ``destination`` must not already exist. The caller owns policy about where
    that resolved directory lives; this function guarantees that no member can
    escape it or turn into a link-like filesystem object.
    """

    requested_destination = Path(destination)
    returned_destination = requested_destination.absolute()
    _reject_linked_path(requested_destination)
    if requested_destination.exists():
        raise ArchiveSafetyError(f"destination already exists: {requested_destination}")

    resolved_destination = requested_destination.resolve(strict=False)
    resolved_parent = resolved_destination.parent
    resolved_parent.mkdir(parents=True, exist_ok=True)
    parent_pins: list[_PinnedDirectory] = []
    temporary_root: Path | None = None
    temporary_root_pin: _PinnedDirectory | None = None
    child_pins: dict[str, _PinnedDirectory] = {}

    try:
        parent_pins = _pin_directory_chain(resolved_parent)
        temporary_root = Path(tempfile.mkdtemp(prefix=".map-extract-", dir=resolved_parent))
        temporary_root_pin = _PinnedDirectory(temporary_root)

        def ensure_pinned_directory(relative_path: Path) -> Path:
            current = temporary_root
            for component in relative_path.parts:
                current = current / component
                current.mkdir(exist_ok=True)
                key = os.path.normcase(os.path.abspath(current))
                if key not in child_pins:
                    child_pins[key] = _PinnedDirectory(current)
            return current

        with zipfile.ZipFile(archive, "r") as source:
            approved = _approve_members(source, limits)
            for member in approved:
                output_path = temporary_root / member.relative_path
                if member.is_directory:
                    ensure_pinned_directory(member.relative_path)
                    continue

                ensure_pinned_directory(member.relative_path.parent)
                written = 0
                with source.open(member.info, "r") as input_stream, _open_new_leaf(output_path) as output_stream:
                    while chunk := input_stream.read(_COPY_CHUNK_SIZE):
                        written += len(chunk)
                        if written > member.info.file_size or written > limits.max_member_size:
                            raise ArchiveSafetyError(
                                f"archive member expanded beyond declared size: {member.info.filename!r}"
                            )
                        output_stream.write(chunk)
                if written != member.info.file_size:
                    raise ArchiveSafetyError(
                        f"archive member size differs from declaration: {member.info.filename!r}"
                    )

        for pin in reversed(list(child_pins.values())):
            pin.close()
        child_pins.clear()
        temporary_root.replace(resolved_destination)
        temporary_root = None
        return [returned_destination / member.relative_path for member in approved if not member.is_directory]
    except (ArchiveSafetyError, zipfile.BadZipFile, RuntimeError, OSError) as error:
        if isinstance(error, ArchiveSafetyError):
            raise
        raise ArchiveSafetyError(f"archive extraction failed safely: {error}") from error
    finally:
        for pin in reversed(list(child_pins.values())):
            pin.close()
        if temporary_root_pin is not None:
            temporary_root_pin.close()
        if temporary_root is not None and temporary_root.exists():
            shutil.rmtree(temporary_root)
        for pin in reversed(parent_pins):
            pin.close()
