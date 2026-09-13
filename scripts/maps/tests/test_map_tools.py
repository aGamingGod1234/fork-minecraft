from __future__ import annotations

import hashlib
import gzip
import http.server
import io
import json
import os
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Iterator
from unittest import mock

from scripts.maps.archive_reader import ArchiveLimits, ArchiveSafetyError, safe_extract


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]

class LocalHttpSource:
    def __init__(
        self,
        payload: bytes,
        wait_for_archive_requests: int | None = None,
        on_archive_request: Callable[[], None] | None = None,
    ) -> None:
        self.payload = payload
        self.requests: dict[str, int] = {}
        self.wait_for_archive_requests = wait_for_archive_requests
        self.on_archive_request = on_archive_request
        self._request_condition = threading.Condition()

        source = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                with source._request_condition:
                    source.requests[self.path] = source.requests.get(self.path, 0) + 1
                    source._request_condition.notify_all()
                    if self.path == "/archive" and source.wait_for_archive_requests is not None:
                        source._request_condition.wait_for(
                            lambda: source.requests.get("/archive", 0) >= source.wait_for_archive_requests,
                            timeout=2,
                        )
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", source.url("/target"))
                    self.end_headers()
                    return
                if self.path in ("/archive", "/target"):
                    if self.path == "/archive" and source.on_archive_request is not None:
                        source.on_archive_request()
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(source.payload)))
                    self.end_headers()
                    self.wfile.write(source.payload)
                    return
                self.send_error(404)

            def log_message(self, format: str, *args: object) -> None:
                pass

        self._server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def url(self, path: str) -> str:
        return f"http://127.0.0.1:{self._server.server_port}{path}"

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)

    def __enter__(self) -> "LocalHttpSource":
        return self

    def __exit__(self, *args: object) -> None:
        self.close()


@contextmanager
def isolated_fetch_repository(
    approved_url: str,
    payload: bytes,
    *,
    ledger_sha256: str | None = None,
) -> Iterator[tuple[Path, Path]]:
    with tempfile.TemporaryDirectory() as temporary_directory:
        root = Path(temporary_directory)
        script_directory = root / "scripts" / "maps"
        script_directory.mkdir(parents=True)
        script_path = script_directory / "fetch_map_source.ps1"
        shutil.copy2(REPOSITORY_ROOT / "scripts" / "maps" / "fetch_map_source.ps1", script_path)

        ledger = {
            "schemaVersion": 1,
            "researchRoot": "runtime/map-research",
            "sources": {
                "local-test": {
                    "licenseStatus": "verified",
                    "bundleEligible": True,
                    "archive": {
                        "url": approved_url,
                        "filename": "fixture.zip",
                        "size": len(payload),
                        "sha1": hashlib.sha1(payload).hexdigest(),
                        "sha512": hashlib.sha512(payload).hexdigest(),
                        "sha256": ledger_sha256,
                    },
                }
            },
        }
        ledger_path = root / "maps" / "source-ledger.json"
        ledger_path.parent.mkdir(parents=True)
        ledger_path.write_text(json.dumps(ledger), encoding="utf-8")
        yield root, script_path


def zip_fixture(entries: list[tuple[str, bytes, int | None]]) -> io.BytesIO:
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for name, content, external_attr in entries:
            member = zipfile.ZipInfo(name)
            member.compress_type = zipfile.ZIP_DEFLATED
            if external_attr is not None:
                member.create_system = 3
                member.external_attr = external_attr
            output.writestr(member, content)
    archive.seek(0)
    return archive


class SafeExtractTests(unittest.TestCase):
    def test_extracts_regular_files_beneath_destination(self) -> None:
        archive = zip_fixture(
            [
                ("world/level.dat", b"level", None),
                ("world/region/r.0.0.mca", b"region", None),
            ]
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            destination = Path(temporary_directory) / "research"
            extracted = safe_extract(archive, destination, ArchiveLimits())

            self.assertEqual(
                [destination / "world" / "level.dat", destination / "world" / "region" / "r.0.0.mca"],
                extracted,
            )
            self.assertEqual(b"level", (destination / "world" / "level.dat").read_bytes())
            self.assertEqual(b"region", (destination / "world" / "region" / "r.0.0.mca").read_bytes())

    def test_accepts_explicit_directory_after_its_child(self) -> None:
        archive = zip_fixture(
            [
                ("world/file.txt", b"safe", None),
                ("world/", b"", (stat.S_IFDIR | 0o755) << 16),
            ]
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            destination = Path(temporary_directory) / "research"
            extracted = safe_extract(archive, destination, ArchiveLimits())

            self.assertEqual([destination / "world" / "file.txt"], extracted)
            self.assertEqual(b"safe", extracted[0].read_bytes())

    def test_rejects_parent_traversal_without_partial_extraction(self) -> None:
        archive = zip_fixture(
            [
                ("world/safe.txt", b"safe", None),
                ("../escape.txt", b"escape", None),
            ]
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            destination = Path(temporary_directory) / "research"
            with self.assertRaisesRegex(ArchiveSafetyError, "traversal"):
                safe_extract(archive, destination, ArchiveLimits())

            self.assertFalse((Path(temporary_directory) / "escape.txt").exists())
            self.assertFalse((destination / "world" / "safe.txt").exists())

    def test_rejects_rooted_and_drive_qualified_paths(self) -> None:
        unsafe_names = ["/absolute.txt", "\\rooted.txt", "C:/drive.txt", "D:relative.txt", "//server/share.txt"]
        for unsafe_name in unsafe_names:
            with self.subTest(unsafe_name=unsafe_name):
                archive = zip_fixture([(unsafe_name, b"unsafe", None)])
                with tempfile.TemporaryDirectory() as temporary_directory:
                    with self.assertRaisesRegex(ArchiveSafetyError, "rooted|drive"):
                        safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_symlink_members(self) -> None:
        symlink_mode = (stat.S_IFLNK | 0o777) << 16
        archive = zip_fixture([("world/link", b"../../escape", symlink_mode)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "link|reparse"):
                safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_reparse_like_members(self) -> None:
        archive = zip_fixture([("world/junction", b"target", 0x0400)])
        with zipfile.ZipFile(archive, "a") as output:
            output.infolist()[-1].create_system = 0

        # Rebuild because changing an in-memory ZipInfo after writing does not update the central directory.
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            member = zipfile.ZipInfo("world/junction")
            member.create_system = 0
            member.external_attr = 0x0400
            output.writestr(member, b"target")
        archive.seek(0)

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "link|reparse"):
                safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_duplicate_normalized_output_paths(self) -> None:
        archive = zip_fixture(
            [
                ("world/region/data.bin", b"one", None),
                ("WORLD\\REGION\\data.bin", b"two", None),
            ]
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "duplicate"):
                safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_member_count_over_limit(self) -> None:
        archive = zip_fixture([("one", b"1", None), ("two", b"2", None)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "member count"):
                safe_extract(
                    archive,
                    Path(temporary_directory) / "research",
                    ArchiveLimits(max_members=1),
                )

    def test_rejects_single_member_over_limit(self) -> None:
        archive = zip_fixture([("large.bin", b"12345", None)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "member size"):
                safe_extract(
                    archive,
                    Path(temporary_directory) / "research",
                    ArchiveLimits(max_member_size=4),
                )

    def test_rejects_total_expanded_size_over_limit(self) -> None:
        archive = zip_fixture([("one.bin", b"123", None), ("two.bin", b"456", None)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "expanded size"):
                safe_extract(
                    archive,
                    Path(temporary_directory) / "research",
                    ArchiveLimits(max_expanded_size=5),
                )

    def test_rejects_extreme_compression_ratio(self) -> None:
        archive = zip_fixture([("bomb.bin", b"0" * 20_000, None)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "compression ratio"):
                safe_extract(
                    archive,
                    Path(temporary_directory) / "research",
                    ArchiveLimits(max_compression_ratio=5.0),
                )

    def test_rejects_windows_alternate_data_stream_paths(self) -> None:
        archive = zip_fixture([("world/level.dat:payload", b"unsafe", None)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "drive|alternate-stream"):
                safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_windows_device_and_ambiguous_paths(self) -> None:
        unsafe_names = ["NUL", "world/CON.txt", "world/name.", "world/name ", "."]
        for unsafe_name in unsafe_names:
            with self.subTest(unsafe_name=unsafe_name):
                archive = zip_fixture([(unsafe_name, b"unsafe", None)])
                with tempfile.TemporaryDirectory() as temporary_directory:
                    with self.assertRaisesRegex(ArchiveSafetyError, "Windows|invalid|traversal"):
                        safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_unicode_dos_device_names(self) -> None:
        unsafe_names = ["COM¹", "world/com².txt", "WORLD/LpT³.log"]
        for unsafe_name in unsafe_names:
            with self.subTest(unsafe_name=unsafe_name):
                archive = zip_fixture([(unsafe_name, b"unsafe", None)])
                with tempfile.TemporaryDirectory() as temporary_directory:
                    with self.assertRaisesRegex(ArchiveSafetyError, "Windows"):
                        safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    @unittest.skipUnless(os.name == "nt", "Windows directory handle semantics")
    def test_parent_junction_swap_before_temporary_creation_cannot_escape(self) -> None:
        archive = zip_fixture([("world/level.dat", b"contained", None)])
        real_mkdtemp = tempfile.mkdtemp
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            parent = root / "validated-parent"
            backup = root / "validated-parent-backup"
            outside = root / "outside"
            parent.mkdir()
            outside.mkdir()
            destination = parent / "destination"

            def swap_parent_then_create(*args: object, **kwargs: object) -> str:
                parent.rename(backup)
                escaped_parent = str(parent).replace("'", "''")
                escaped_outside = str(outside).replace("'", "''")
                subprocess.run(
                    [
                        "powershell",
                        "-NoProfile",
                        "-Command",
                        f"New-Item -ItemType Junction -Path '{escaped_parent}' "
                        f"-Target '{escaped_outside}' | Out-Null",
                    ],
                    check=True,
                    capture_output=True,
                    text=True,
                )
                return real_mkdtemp(*args, **kwargs)

            escaped = False
            try:
                with mock.patch("scripts.maps.archive_reader.tempfile.mkdtemp", side_effect=swap_parent_then_create):
                    with self.assertRaises(ArchiveSafetyError):
                        safe_extract(archive, destination, ArchiveLimits())
                escaped = (outside / "destination" / "world" / "level.dat").exists()
            finally:
                if parent.exists() or parent.is_symlink():
                    parent.rmdir()
                if backup.exists():
                    backup.rename(parent)

            self.assertFalse(escaped)


class SourceLedgerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.ledger = json.loads((REPOSITORY_ROOT / "maps" / "source-ledger.json").read_text(encoding="utf-8"))

    def test_restructured_has_verified_exact_version_and_retained_license(self) -> None:
        source = self.ledger["sources"]["re-structured"]

        self.assertEqual("ShB7QWuY", source["projectId"])
        self.assertEqual("NNsq5KuW", source["versionId"])
        self.assertEqual("1.2", source["version"])
        self.assertEqual(
            "https://cdn.modrinth.com/data/ShB7QWuY/versions/NNsq5KuW/re-structured.zip",
            source["archive"]["url"],
        )
        self.assertEqual("verified", source["licenseStatus"])
        self.assertTrue(source["bundleEligible"])
        self.assertEqual("MIT", source["license"]["spdx"])

        retained_license = REPOSITORY_ROOT / source["license"]["retainedText"]
        self.assertEqual(
            "a6814afbcf66038d02c80d905d4969b944f1d89b872bf89bc9699c7d1391ef31",
            hashlib.sha256(retained_license.read_bytes()).hexdigest(),
        )
        self.assertEqual(source["license"]["sha256"], hashlib.sha256(retained_license.read_bytes()).hexdigest())

    def test_curseforge_sources_remain_provisional_and_not_acquirable(self) -> None:
        expected = {
            "parkour-masters": (
                "1020454",
                "5353095",
                "https://www.curseforge.com/minecraft/worlds/parkour-masters/files/5353095",
            ),
            "minegpt-worlds": (
                "1092626",
                "5798831",
                "https://www.curseforge.com/minecraft/worlds/bigyous-minegpt-worlds/files/5798831",
            ),
            "bunker-survival": (
                "1010696",
                "5300058",
                "https://www.curseforge.com/minecraft/worlds/bunker-survival/files/5300058",
            ),
        }

        for source_key, (project_id, file_id, landing_url) in expected.items():
            with self.subTest(source_key=source_key):
                source = self.ledger["sources"][source_key]
                self.assertEqual(project_id, source["projectId"])
                self.assertEqual(file_id, source["fileId"])
                self.assertEqual(landing_url, source["officialLandingUrl"])
                self.assertEqual("declared-platform-awaiting-retained-text", source["licenseStatus"])
                self.assertFalse(source["bundleEligible"])
                self.assertIsNone(source["archive"]["url"])


class FetchMapSourceTests(unittest.TestCase):
    def run_fetch(self, source_key: str, destination: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(REPOSITORY_ROOT / "scripts" / "maps" / "fetch_map_source.ps1"),
                "-SourceKey",
                source_key,
                "-Destination",
                str(destination),
            ],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )

    def run_isolated_fetch(
        self,
        repository: Path,
        script_path: Path,
        destination: Path,
        transport_url: str,
        *,
        fail_after_publish: int | None = None,
        kill_after_publish: int | None = None,
        external_collision_at_publish: int | None = None,
        external_collision_bytes: bytes = b"external writer",
        replace_after_publish: int | None = None,
        replacement_bytes: bytes = b"external replacement",
        destination_junction_target: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        instrumented_script_path = script_path
        if any(
            value is not None
            for value in (
                fail_after_publish,
                kill_after_publish,
                external_collision_at_publish,
                replace_after_publish,
            )
        ):
            script_text = script_path.read_text(encoding="utf-8")
            current_statements = [
                "    Move-Item -LiteralPath $partialPath -Destination $archivePath",
                "        Move-Item -LiteralPath $partialEvidencePath -Destination $evidencePath",
            ]
            owned_statements = [
                "    Publish-OwnedFile -PartialPath $partialPath -FinalPath $archivePath -Owned ([ref]$archivePublishedByThisRun)",
                "    Publish-OwnedFile -PartialPath $partialEvidencePath -FinalPath $evidencePath -Owned ([ref]$evidencePublishedByThisRun)",
            ]
            statements = owned_statements if all(statement in script_text for statement in owned_statements) else current_statements
            if not all(statement in script_text for statement in statements):
                raise AssertionError("Map publication statements could not be instrumented")
            for publish_index, statement in enumerate(statements, start=1):
                target_path = "$archivePath" if publish_index == 1 else "$evidencePath"
                before = ""
                after = ""
                if external_collision_at_publish == publish_index:
                    encoded_bytes = ",".join(str(value) for value in external_collision_bytes)
                    before = (
                        f"    [System.IO.File]::WriteAllBytes({target_path}, [byte[]]@({encoded_bytes}))\n"
                        if publish_index == 1
                        else f"        [System.IO.File]::WriteAllBytes({target_path}, [byte[]]@({encoded_bytes}))\n"
                    )
                if fail_after_publish == publish_index:
                    after = "\n        throw 'Injected failure after owned publication.'" if publish_index == 2 else "\n    throw 'Injected failure after owned publication.'"
                if kill_after_publish == publish_index:
                    after = "\n        Stop-Process -Id $PID -Force" if publish_index == 2 else "\n    Stop-Process -Id $PID -Force"
                if replace_after_publish == publish_index:
                    encoded_bytes = ",".join(str(value) for value in replacement_bytes)
                    indentation = "        " if publish_index == 2 else "    "
                    after = (
                        f"\n{indentation}Remove-Item -LiteralPath {target_path} -Force"
                        f"\n{indentation}[System.IO.File]::WriteAllBytes({target_path}, [byte[]]@({encoded_bytes}))"
                        f"\n{indentation}throw 'Injected failure after external replacement.'"
                    )
                script_text = script_text.replace(statement, before + statement + after, 1)
            instrumented_script_path = script_path.with_name("fetch_map_source.instrumented.ps1")
            instrumented_script_path.write_text(script_text, encoding="utf-8")

        if destination_junction_target is not None:
            script_text = instrumented_script_path.read_text(encoding="utf-8")
            creation = "New-Item -ItemType Directory -Path $destinationPath -Force | Out-Null"
            escaped_target = str(destination_junction_target).replace("'", "''")
            swap = (
                creation
                + "\nRemove-Item -LiteralPath $destinationPath -Force"
                + f"\nNew-Item -ItemType Junction -Path $destinationPath -Target '{escaped_target}' | Out-Null"
            )
            if creation not in script_text:
                raise AssertionError("Destination creation could not be instrumented")
            script_text = script_text.replace(creation, swap, 1)
            instrumented_script_path = script_path.with_name("fetch_map_source.instrumented.ps1")
            instrumented_script_path.write_text(script_text, encoding="utf-8")

        escaped_script = str(instrumented_script_path).replace("'", "''")
        escaped_destination = str(destination).replace("'", "''")
        escaped_transport_url = transport_url.replace("'", "''")
        command = (
            "function Invoke-WebRequest { param([switch]$UseBasicParsing, [int]$MaximumRedirection, "
            "[uri]$Uri, [string]$OutFile); "
            f"$parameters = @{{ UseBasicParsing = $true; Uri = '{escaped_transport_url}'; "
            "OutFile = $OutFile }; "
            "if ($PSBoundParameters.ContainsKey('MaximumRedirection')) { "
            "$parameters.MaximumRedirection = $MaximumRedirection }; "
            "Microsoft.PowerShell.Utility\\Invoke-WebRequest @parameters }; "
            f"& '{escaped_script}' -SourceKey local-test -Destination '{escaped_destination}'"
        )
        return subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
            cwd=repository,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )

    def test_local_acquisition_publishes_pair_and_reuses_it(self) -> None:
        payload = b"local map archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "success"

                first = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))
                second = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, first.returncode, first.stderr)
                self.assertEqual(0, second.returncode, second.stderr)
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                self.assertTrue((destination / "fixture.zip.sha256.json").is_file())
                self.assertIn("False", first.stdout)
                self.assertIn("True", second.stdout)

    def test_concurrent_same_destination_serializes_initial_acquisition(self) -> None:
        payload = b"concurrent map archive"
        with LocalHttpSource(payload, wait_for_archive_requests=2) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "concurrent"

                with ThreadPoolExecutor(max_workers=2) as executor:
                    futures = [
                        executor.submit(
                            self.run_isolated_fetch,
                            repository,
                            script_path,
                            destination,
                            source.url("/archive"),
                        )
                        for _ in range(2)
                    ]
                    results = [future.result(timeout=20) for future in futures]

                archive_path = destination / "fixture.zip"
                evidence_path = destination / "fixture.zip.sha256.json"
                self.assertTrue(archive_path.is_file())
                self.assertTrue(evidence_path.is_file())
                self.assertEqual(payload, archive_path.read_bytes())
                evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
                self.assertEqual(hashlib.sha256(payload).hexdigest(), evidence["sha256"])
                self.assertEqual([0, 0], sorted(result.returncode for result in results))
                self.assertEqual(1, source.requests.get("/archive", 0))
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

    def test_concurrent_different_destinations_acquire_independently(self) -> None:
        payload = b"independent map archive"
        with LocalHttpSource(payload, wait_for_archive_requests=2) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destinations = [
                    repository / "runtime" / "map-research" / "destination-a",
                    repository / "runtime" / "map-research" / "destination-b",
                ]

                with ThreadPoolExecutor(max_workers=2) as executor:
                    futures = [
                        executor.submit(
                            self.run_isolated_fetch,
                            repository,
                            script_path,
                            destination,
                            source.url("/archive"),
                        )
                        for destination in destinations
                    ]
                    results = [future.result(timeout=20) for future in futures]

                self.assertEqual([0, 0], sorted(result.returncode for result in results))
                self.assertEqual(2, source.requests.get("/archive", 0))
                for destination in destinations:
                    self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                    self.assertTrue((destination / "fixture.zip.sha256.json").is_file())

    def test_rejects_redirect_without_contacting_unapproved_target(self) -> None:
        payload = b"redirected map archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/redirect", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "redirect"

                result = self.run_isolated_fetch(repository, script_path, destination, source.url("/redirect"))

                self.assertEqual(1, source.requests.get("/redirect", 0))
                self.assertEqual(0, source.requests.get("/target", 0))
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((destination / "fixture.zip").exists())
                self.assertFalse((destination / "fixture.zip.sha256.json").exists())
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

                retry = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))
                self.assertEqual(0, retry.returncode, retry.stderr)
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                self.assertTrue((destination / "fixture.zip.sha256.json").is_file())

    def test_evidence_promotion_failure_rolls_back_entire_pair(self) -> None:
        payload = b"transaction map archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "rollback"

                result = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    fail_after_publish=2,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertIn("after owned publication", (result.stdout + result.stderr).lower())
                self.assertFalse((destination / "fixture.zip").exists())
                self.assertFalse((destination / "fixture.zip.sha256.json").exists())
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

    def test_external_archive_collision_is_not_deleted_by_rollback(self) -> None:
        payload = b"archive collision download"
        external_bytes = b"external archive owner"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "archive-collision"

                result = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    external_collision_at_publish=1,
                    external_collision_bytes=external_bytes,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertTrue((destination / "fixture.zip").is_file())
                self.assertEqual(external_bytes, (destination / "fixture.zip").read_bytes())
                self.assertFalse((destination / "fixture.zip.sha256.json").exists())
                self.assertTrue((destination / ".arenaagents-acquisition.journal.json").is_file())

    def test_external_evidence_collision_is_not_deleted_by_rollback(self) -> None:
        payload = b"evidence collision download"
        external_bytes = b"external evidence owner"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "evidence-collision"

                result = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    external_collision_at_publish=2,
                    external_collision_bytes=external_bytes,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                self.assertTrue((destination / "fixture.zip.sha256.json").is_file())
                self.assertEqual(external_bytes, (destination / "fixture.zip.sha256.json").read_bytes())
                self.assertTrue((destination / ".arenaagents-acquisition.journal.json").is_file())

    def test_replaced_owned_archive_is_preserved_on_rollback(self) -> None:
        payload = b"owned archive before replacement"
        replacement = b"replacement archive from external writer"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "replace-archive"

                result = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    replace_after_publish=1,
                    replacement_bytes=replacement,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertTrue((destination / "fixture.zip").is_file())
                self.assertEqual(replacement, (destination / "fixture.zip").read_bytes())

    def test_replaced_owned_evidence_is_preserved_on_rollback(self) -> None:
        payload = b"owned evidence before replacement"
        replacement = b"replacement evidence from external writer"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "replace-evidence"

                result = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    replace_after_publish=2,
                    replacement_bytes=replacement,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertTrue((destination / "fixture.zip.sha256.json").is_file())
                self.assertEqual(replacement, (destination / "fixture.zip.sha256.json").read_bytes())

    def test_hard_kill_after_archive_promotion_recovers_on_retry(self) -> None:
        payload = b"hard-kill map archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "hard-kill-first"

                interrupted = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    kill_after_publish=1,
                )
                self.assertNotEqual(0, interrupted.returncode)
                self.assertTrue((destination / "fixture.zip").is_file())
                self.assertFalse((destination / "fixture.zip.sha256.json").exists())
                self.assertTrue((destination / ".arenaagents-acquisition.journal.json").is_file())

                retry = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))
                reuse = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, retry.returncode, retry.stderr)
                self.assertEqual(0, reuse.returncode, reuse.stderr)
                self.assertIn("False", retry.stdout)
                self.assertIn("True", reuse.stdout)
                self.assertEqual(2, source.requests.get("/archive", 0))
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                evidence = json.loads((destination / "fixture.zip.sha256.json").read_text(encoding="utf-8"))
                self.assertEqual(hashlib.sha256(payload).hexdigest(), evidence["sha256"])
                self.assertFalse((destination / ".arenaagents-acquisition.journal.json").exists())
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

    def test_unowned_archive_only_is_preserved_and_fails_closed(self) -> None:
        payload = b"ledger-locked orphan archive"
        approved_url = "https://approved.invalid/archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository(
                approved_url,
                payload,
                ledger_sha256=hashlib.sha256(payload).hexdigest(),
            ) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "archive-only"
                destination.mkdir(parents=True)
                (destination / "fixture.zip").write_bytes(payload)

                result = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertNotEqual(0, result.returncode)
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                self.assertEqual(0, source.requests.get("/archive", 0))
                self.assertFalse((destination / "fixture.zip.sha256.json").exists())

    def test_unowned_evidence_only_is_preserved_and_fails_closed(self) -> None:
        payload = b"orphan evidence archive"
        approved_url = "https://approved.invalid/archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository(approved_url, payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "evidence-only"
                destination.mkdir(parents=True)
                evidence_path = destination / "fixture.zip.sha256.json"
                evidence_path.write_text("not trusted as evidence", encoding="utf-8")

                result = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertNotEqual(0, result.returncode)
                self.assertEqual("not trusted as evidence", evidence_path.read_text(encoding="utf-8"))
                self.assertEqual(0, source.requests.get("/archive", 0))
                self.assertFalse((destination / "fixture.zip").exists())

    def test_interrupted_recovery_removes_only_source_scoped_uuid_partials(self) -> None:
        payload = b"stale partial recovery archive"
        approved_url = "https://approved.invalid/archive"
        acquisition_id = "a" * 32
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository(
                approved_url,
                payload,
                ledger_sha256=hashlib.sha256(payload).hexdigest(),
            ) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "stale-partials"
                destination.mkdir(parents=True)
                (destination / "fixture.zip").write_bytes(payload)
                stale_archive_partial = destination / f".fixture.zip.{acquisition_id}.partial"
                stale_evidence_partial = destination / f"fixture.zip.sha256.json.{acquisition_id}.partial"
                unrelated_partial = destination / f".other.zip.{acquisition_id}.partial"
                stale_archive_partial.write_bytes(b"stale")
                stale_evidence_partial.write_bytes(b"stale")
                unrelated_partial.write_bytes(b"unrelated")

                result = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertNotEqual(0, result.returncode)
                self.assertTrue(stale_archive_partial.exists())
                self.assertTrue(stale_evidence_partial.exists())
                self.assertEqual(b"unrelated", unrelated_partial.read_bytes())
                self.assertTrue((destination / "fixture.zip").is_file())
                self.assertFalse((destination / "fixture.zip.sha256.json").exists())

    def test_hard_kill_after_evidence_promotion_leaves_valid_pair_for_reuse(self) -> None:
        payload = b"hard-kill complete pair"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "hard-kill-second"

                interrupted = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    kill_after_publish=2,
                )
                self.assertNotEqual(0, interrupted.returncode)
                self.assertTrue((destination / "fixture.zip").is_file())
                self.assertTrue((destination / "fixture.zip.sha256.json").is_file())
                self.assertTrue((destination / ".arenaagents-acquisition.journal.json").is_file())

                reuse = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, reuse.returncode, reuse.stderr)
                self.assertIn("True", reuse.stdout)
                self.assertEqual(1, source.requests.get("/archive", 0))
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                self.assertFalse((destination / ".arenaagents-acquisition.journal.json").exists())

    def test_unowned_source_partials_are_preserved_when_neither_final_exists(self) -> None:
        payload = b"neither-final stale partial archive"
        acquisition_id = "b" * 32
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "neither-stale"
                destination.mkdir(parents=True)
                stale_archive = destination / f".fixture.zip.{acquisition_id}.partial"
                stale_evidence = destination / f"fixture.zip.sha256.json.{acquisition_id}.partial"
                unrelated = destination / f".other.zip.{acquisition_id}.partial"
                stale_archive.write_bytes(b"stale")
                stale_evidence.write_bytes(b"stale")
                unrelated.write_bytes(b"unrelated")

                result = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertTrue(stale_archive.exists())
                self.assertTrue(stale_evidence.exists())
                self.assertEqual(b"unrelated", unrelated.read_bytes())

    def test_complete_pair_reuse_preserves_unowned_source_partials(self) -> None:
        payload = b"complete pair stale partial archive"
        acquisition_id = "c" * 32
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "complete-stale"
                first = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))
                self.assertEqual(0, first.returncode, first.stderr)
                stale_archive = destination / f".fixture.zip.{acquisition_id}.partial"
                stale_evidence = destination / f"fixture.zip.sha256.json.{acquisition_id}.partial"
                stale_archive.write_bytes(b"stale")
                stale_evidence.write_bytes(b"stale")

                reuse = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, reuse.returncode, reuse.stderr)
                self.assertIn("True", reuse.stdout)
                self.assertEqual(1, source.requests.get("/archive", 0))
                self.assertTrue(stale_archive.exists())
                self.assertTrue(stale_evidence.exists())

    @unittest.skipUnless(os.name == "nt", "Windows directory handle semantics")
    def test_destination_junction_swap_before_pinning_cannot_write_outside(self) -> None:
        payload = b"junction-contained map archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "junction-race"
                outside = repository / "outside"
                outside.mkdir()

                result = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    destination_junction_target=outside,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertFalse((outside / "fixture.zip").exists())
                self.assertFalse((outside / "fixture.zip.sha256.json").exists())

    def test_hostile_expected_paths_fail_closed_without_deletion(self) -> None:
        payload = b"hostile final path archive"
        approved_url = "https://approved.invalid/archive"
        for hostile_name in ("fixture.zip", "fixture.zip.sha256.json", ".arenaagents-acquisition.lock"):
            with self.subTest(hostile_name=hostile_name):
                with LocalHttpSource(payload) as source:
                    with isolated_fetch_repository(
                        approved_url,
                        payload,
                        ledger_sha256=hashlib.sha256(payload).hexdigest(),
                    ) as (repository, script_path):
                        destination = repository / "runtime" / "map-research" / "hostile"
                        destination.mkdir(parents=True)
                        hostile_path = destination / hostile_name
                        hostile_path.mkdir()

                        result = self.run_isolated_fetch(
                            repository,
                            script_path,
                            destination,
                            source.url("/archive"),
                        )

                        self.assertNotEqual(0, result.returncode)
                        self.assertTrue(hostile_path.is_dir())
                        self.assertEqual(0, source.requests.get("/archive", 0))

    def test_rejects_unknown_source_before_network_access(self) -> None:
        result = self.run_fetch("not-in-ledger", REPOSITORY_ROOT / "runtime" / "map-research" / "test")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("not present in the source ledger", result.stderr)

    def test_rejects_destination_outside_ignored_research_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            result = self.run_fetch("re-structured", Path(temporary_directory) / "download")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("runtime/map-research", result.stderr.replace("\\", "/"))

    def test_rejects_existing_archive_with_wrong_ledger_size(self) -> None:
        research_root = REPOSITORY_ROOT / "runtime" / "map-research"
        research_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=research_root) as temporary_directory:
            destination = Path(temporary_directory)
            archive = destination / "re-structured.zip"
            archive.write_bytes(b"not the approved archive")
            evidence = {
                "schemaVersion": 1,
                "sourceKey": "re-structured",
                "url": "https://cdn.modrinth.com/data/ShB7QWuY/versions/NNsq5KuW/re-structured.zip",
                "filename": "re-structured.zip",
                "retrievedAtUtc": "2026-08-19T00:00:00Z",
                "size": archive.stat().st_size,
                "sha256": "4b552b9100bebf8dece5faf1bedd586be318a03d7279d2ae10b4a53b000a1d33",
            }
            (destination / "re-structured.zip.sha256.json").write_text(
                json.dumps(evidence), encoding="utf-8"
            )

            result = self.run_fetch("re-structured", destination)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("size", result.stderr.lower())

    def test_rejects_self_attested_sha256_when_official_sha512_differs(self) -> None:
        research_root = REPOSITORY_ROOT / "runtime" / "map-research"
        research_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=research_root) as temporary_directory:
            destination = Path(temporary_directory)
            archive = destination / "re-structured.zip"
            archive.write_bytes(b"x" * 97_543)
            evidence = {
                "schemaVersion": 1,
                "sourceKey": "re-structured",
                "url": "https://cdn.modrinth.com/data/ShB7QWuY/versions/NNsq5KuW/re-structured.zip",
                "filename": "re-structured.zip",
                "retrievedAtUtc": "2026-08-19T00:00:00Z",
                "size": archive.stat().st_size,
                "sha256": "4b552b9100bebf8dece5faf1bedd586be318a03d7279d2ae10b4a53b000a1d33",
            }
            (destination / "re-structured.zip.sha256.json").write_text(
                json.dumps(evidence), encoding="utf-8"
            )

            result = self.run_fetch("re-structured", destination)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("sha512", result.stderr.lower())


def mutf8(value: str) -> bytes:
    encoded = bytearray()
    utf16 = value.encode("utf-16-be")
    for offset in range(0, len(utf16), 2):
        unit = int.from_bytes(utf16[offset : offset + 2], "big")
        if 0x0001 <= unit <= 0x007F:
            encoded.append(unit)
        elif unit <= 0x07FF:
            encoded.extend((0xC0 | unit >> 6, 0x80 | unit & 0x3F))
        else:
            encoded.extend((0xE0 | unit >> 12, 0x80 | unit >> 6 & 0x3F, 0x80 | unit & 0x3F))
    return struct.pack(">H", len(encoded)) + bytes(encoded)


def nbt_named(tag_id: int, name: str, payload: bytes) -> bytes:
    return bytes((tag_id,)) + mutf8(name) + payload


def nbt_compound(entries: list[tuple[int, str, bytes]]) -> bytes:
    return b"".join(nbt_named(tag_id, name, payload) for tag_id, name, payload in entries) + b"\x00"


def nbt_list(element_tag: int, payloads: list[bytes]) -> bytes:
    return bytes((element_tag,)) + struct.pack(">i", len(payloads)) + b"".join(payloads)


def nbt_structure(
    *,
    data_version: int = 4790,
    size: tuple[int, int, int] = (2, 2, 1),
    palette: list[tuple[str, dict[str, str]]] | None = None,
    blocks: list[tuple[tuple[int, int, int], int, bytes | None]] | None = None,
    entities: list[bytes] | None = None,
    use_palettes: bool = False,
    empty_entities_tag: int = 10,
) -> bytes:
    palette = palette or [("minecraft:stone", {})]
    blocks = blocks or [((0, 0, 0), 0, None)]
    palette_payloads = []
    for block_id, properties in palette:
        entries = [(8, "Name", mutf8(block_id))]
        if properties:
            entries.append(
                (10, "Properties", nbt_compound([(8, key, mutf8(value)) for key, value in properties.items()]))
            )
        palette_payloads.append(nbt_compound(entries))
    block_payloads = []
    for position, state, block_nbt in blocks:
        entries = [
            (9, "pos", nbt_list(3, [struct.pack(">i", value) for value in position])),
            (3, "state", struct.pack(">i", state)),
        ]
        if block_nbt is not None:
            entries.append((10, "nbt", block_nbt))
        block_payloads.append(nbt_compound(entries))
    root_entries = [
        (3, "DataVersion", struct.pack(">i", data_version)),
        (9, "size", nbt_list(3, [struct.pack(">i", value) for value in size])),
        (9, "blocks", nbt_list(10, block_payloads)),
        (9, "entities", nbt_list(empty_entities_tag if not entities else 10, entities or [])),
    ]
    if use_palettes:
        root_entries.append((9, "palettes", nbt_list(9, [nbt_list(10, palette_payloads)])))
    else:
        root_entries.append((9, "palette", nbt_list(10, palette_payloads)))
    return nbt_named(10, "structure", nbt_compound(root_entries))


class BoundedNbtReaderTests(unittest.TestCase):
    def read(self, payload: bytes, **limit_overrides: int):
        from scripts.maps.nbt_reader import NbtLimits, read_bounded

        return read_bounded(io.BytesIO(payload), NbtLimits(**limit_overrides))

    def test_reads_raw_and_single_member_gzip_equivalently(self) -> None:
        payload = nbt_named(10, "root", nbt_compound([(3, "value", struct.pack(">i", 42))]))

        self.assertEqual(self.read(payload), self.read(gzip.compress(payload, mtime=0)))

    def test_preserves_every_supported_tag_type_and_modified_utf8(self) -> None:
        entries = [
            (1, "byte", struct.pack(">b", -2)),
            (2, "short", struct.pack(">h", -300)),
            (3, "int", struct.pack(">i", 123456)),
            (4, "long", struct.pack(">q", -123456789)),
            (5, "float", struct.pack(">f", 1.25)),
            (6, "double", struct.pack(">d", -2.5)),
            (7, "bytes", struct.pack(">i", 3) + b"\x00\x7f\xff"),
            (8, "text", mutf8("nul\x00 emoji \U0001f642")),
            (9, "list", nbt_list(2, [struct.pack(">h", 7), struct.pack(">h", 8)])),
            (10, "compound", nbt_compound([(8, "nested", mutf8("yes"))])),
            (11, "ints", struct.pack(">i", 2) + struct.pack(">ii", -1, 2)),
            (12, "longs", struct.pack(">i", 2) + struct.pack(">qq", -3, 4)),
        ]

        root = self.read(nbt_named(10, "root", nbt_compound(entries)))

        self.assertEqual(10, root.tag_id)
        self.assertEqual("nul\x00 emoji \U0001f642", root.value["text"].value)
        self.assertEqual(bytes((0, 127, 255)), root.value["bytes"].value)
        self.assertEqual((-1, 2), root.value["ints"].value)
        self.assertEqual((-3, 4), root.value["longs"].value)
        self.assertEqual(2, root.value["list"].value.element_tag_id)

    def test_rejects_malformed_roots_lengths_duplicates_and_nonfinite_numbers(self) -> None:
        from scripts.maps.nbt_reader import NbtError

        malformed = {
            "named end": nbt_named(0, "bad", b""),
            "truncated": nbt_named(10, "root", b""),
            "duplicate": nbt_named(
                10,
                "root",
                nbt_compound([(3, "x", struct.pack(">i", 1)), (3, "x", struct.pack(">i", 2))]),
            ),
            "negative array": nbt_named(10, "root", nbt_compound([(7, "x", struct.pack(">i", -1))])),
            "nonzero end list": nbt_named(10, "root", nbt_compound([(9, "x", nbt_list(0, [b""]))])),
            "nan": nbt_named(10, "root", nbt_compound([(5, "x", struct.pack(">f", float("nan")))])),
            "bad mutf8": b"\x0a\x00\x01\x00\x00",
            "trailing root": nbt_named(10, "root", b"\x00") + nbt_named(10, "two", b"\x00"),
        }
        for name, payload in malformed.items():
            with self.subTest(name=name), self.assertRaises(NbtError):
                self.read(payload)

    def test_rejects_corrupt_or_concatenated_gzip(self) -> None:
        from scripts.maps.nbt_reader import NbtError

        payload = nbt_named(10, "root", b"\x00")
        corrupt = bytearray(gzip.compress(payload, mtime=0))
        corrupt[-1] ^= 0xFF
        for compressed in (bytes(corrupt), gzip.compress(payload, mtime=0) + gzip.compress(payload, mtime=0)):
            with self.assertRaises(NbtError):
                self.read(compressed)

    def test_enforces_depth_node_string_list_compound_array_and_total_byte_limits(self) -> None:
        from scripts.maps.nbt_reader import NbtError

        cases = [
            (nbt_named(10, "root", nbt_compound([(8, "x", mutf8("abcd"))])), {"max_string_bytes": 3}),
            (nbt_named(10, "root", nbt_compound([(9, "x", nbt_list(1, [b"\x01", b"\x02"]))])), {"max_list_elements": 1}),
            (nbt_named(10, "root", nbt_compound([(1, "a", b"\x01"), (1, "b", b"\x02")])), {"max_compound_entries": 1}),
            (nbt_named(10, "root", nbt_compound([(7, "x", struct.pack(">i", 2) + b"ab")])), {"max_array_payload_bytes": 1}),
            (nbt_named(10, "root", nbt_compound([(11, "x", struct.pack(">i", 2) + struct.pack(">ii", 1, 2))])), {"max_array_elements": 1}),
            (nbt_named(10, "root", nbt_compound([(1, "a", b"\x01"), (1, "b", b"\x02")])), {"max_nodes": 2}),
            (nbt_named(10, "root", nbt_compound([(10, "x", nbt_compound([(10, "y", b"\x00")]))])), {"max_depth": 2}),
        ]
        for payload, limits in cases:
            with self.subTest(limits=limits), self.assertRaises(NbtError):
                self.read(payload, **limits)


class MapConversionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.output_root = self.root / "output"
        self.output_root.mkdir()
        self.catalog = self.root / "catalog.json"
        self.catalog.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "minecraftVersion": "26.1.2",
                    "dataVersion": 4790,
                    "states": [
                        "minecraft:air",
                        "minecraft:chest[facing=north,type=single,waterlogged=false]",
                        "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
                        "minecraft:stone",
                    ],
                }
            ),
            encoding="utf-8",
        )
        self.ledger = self.root / "source-ledger.json"
        self.ledger.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "sources": {
                        "project-owned-fixtures": {
                            "origin": "project-owned",
                            "bundleEligible": True,
                            "licenseStatus": "project-owned",
                            "archive": None,
                        }
                    },
                }
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_case(
        self,
        payload: bytes,
        *,
        selection_changes: dict[str, object] | None = None,
        filename: str = "room.nbt",
    ) -> tuple[Path, Path]:
        source_path = self.source / filename
        source_path.write_bytes(payload)
        selection = {
            "schemaVersion": 1,
            "id": "test-room",
            "version": 1,
            "sourceKey": "project-owned-fixtures",
            "input": {"path": filename, "sha256": hashlib.sha256(payload).hexdigest()},
            "difficulty": 1,
            "bounds": {"min": [0, 0, 0], "max": [1, 1, 0]},
            "allowedTransforms": ["identity", "rotate_90"],
            "containerPolicy": "none",
            "spectatorPolicy": "separated",
            "expectedRemoved": {"entities": 0, "blockEntities": 0},
            "anchors": [
                {"id": "spawn-1", "type": "spawn", "position": [0, 1, 0]},
                {"id": "goal-1", "type": "goal", "position": [1, 1, 0]},
            ],
        }
        if selection_changes:
            selection.update(selection_changes)
        selection_path = self.root / "selection.json"
        selection_path.write_text(json.dumps(selection), encoding="utf-8")
        return selection_path, self.output_root / "module.json"

    def convert(self, selection_path: Path, output_path: Path):
        from scripts.maps.convert_map_module import convert_selection

        return convert_selection(
            selection_path,
            self.source,
            output_path,
            repository_root=self.root,
            output_root=self.output_root,
            catalog_path=self.catalog,
            ledger_path=self.ledger,
        )

    def test_converts_to_canonical_block_only_json_and_verifies_hash(self) -> None:
        payload = nbt_structure(
            palette=[("minecraft:air", {}), ("minecraft:stone", {})],
            blocks=[((1, 0, 0), 1, None), ((0, 0, 0), 0, None)],
        )
        selection, output = self.write_case(
            payload,
            selection_changes={
                "allowedTransforms": ["identity", "rotate_90", "rotate_180", "rotate_270"]
            },
        )

        module = self.convert(selection, output)

        self.assertEqual([{"id": "minecraft:stone", "properties": {}}], module["palette"])
        self.assertEqual([{"state": 0, "x": 1, "y": 0, "z": 0}], module["placements"])
        self.assertEqual(
            ["identity", "rotate_90", "rotate_180", "rotate_270"],
            module["allowedTransforms"],
        )
        expected_hash = hashlib.sha256(b"1,0,0=minecraft:stone\n").hexdigest()
        self.assertEqual(expected_hash, module["geometrySha256"])
        self.assertEqual(b"\n", output.read_bytes()[-1:])
        self.assertNotIn("nbt", output.read_text(encoding="utf-8").lower())

        from scripts.maps.verify_map_modules import verify_module

        verify_module(output, self.catalog)

    def test_accepts_vanilla_end_typed_empty_entities_list_only_when_empty(self) -> None:
        payload = nbt_structure(empty_entities_tag=0)
        selection, output = self.write_case(payload)

        module = self.convert(selection, output)

        self.assertEqual("test-room", module["id"])
        from scripts.maps.inspect_map_source import inspect_structure

        self.assertEqual(0, inspect_structure(self.source / "room.nbt")["entityCount"])

    def test_equivalent_palette_compound_and_block_order_is_byte_identical(self) -> None:
        first = nbt_structure(
            palette=[("minecraft:stone", {}), ("minecraft:air", {})],
            blocks=[((1, 0, 0), 0, None), ((0, 0, 0), 1, None)],
        )
        second = nbt_structure(
            palette=[("minecraft:air", {}), ("minecraft:stone", {})],
            blocks=[((0, 0, 0), 0, None), ((1, 0, 0), 1, None)],
        )
        selection, output = self.write_case(first)
        self.convert(selection, output)
        first_bytes = output.read_bytes()
        selection, output = self.write_case(second)
        self.convert(selection, output)

        self.assertEqual(first_bytes, output.read_bytes())

    def test_requires_exact_data_version_input_hash_and_removed_counts_atomically(self) -> None:
        cases = [
            (nbt_structure(data_version=4789), {}, "DataVersion"),
            (nbt_structure(), {"input": {"path": "room.nbt", "sha256": "0" * 64}}, "sha256"),
            (
                nbt_structure(blocks=[((0, 0, 0), 0, nbt_compound([(8, "note", mutf8("owned"))]))]),
                {},
                "blockEntities",
            ),
            (nbt_structure(entities=[nbt_compound([])]), {}, "entities"),
        ]
        for payload, changes, message in cases:
            with self.subTest(message=message):
                selection, output = self.write_case(payload, selection_changes=changes)
                output.write_text("sentinel", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, message):
                    self.convert(selection, output)
                self.assertEqual("sentinel", output.read_text(encoding="utf-8"))

    def test_rejects_anvil_world_multi_palette_duplicate_coordinates_and_bad_palette_index(self) -> None:
        cases = [
            (nbt_structure(), "region.mca", "Anvil"),
            (nbt_structure(use_palettes=True), "room.nbt", "multi-palette"),
            (nbt_structure(blocks=[((0, 0, 0), 0, None), ((0, 0, 0), 0, None)]), "room.nbt", "duplicate"),
            (nbt_structure(blocks=[((0, 0, 0), 4, None)]), "room.nbt", "palette index"),
        ]
        for payload, filename, message in cases:
            with self.subTest(message=message):
                selection, output = self.write_case(payload, filename=filename)
                with self.assertRaisesRegex(ValueError, message):
                    self.convert(selection, output)

        directory = self.source / "world"
        directory.mkdir()
        selection, output = self.write_case(nbt_structure(), selection_changes={"input": {"path": "world", "sha256": "0" * 64}})
        with self.assertRaisesRegex(ValueError, "world director"):
            self.convert(selection, output)

    def test_rejects_unknown_partial_or_impossible_states_and_control_blocks(self) -> None:
        states = [
            ("minecraft:not_real", {}, "catalog"),
            ("minecraft:oak_stairs", {"facing": "north"}, "exact complete"),
            (
                "minecraft:oak_stairs",
                {"facing": "up", "half": "bottom", "shape": "straight", "waterlogged": "false"},
                "catalog",
            ),
            ("minecraft:command_block", {"conditional": "false", "facing": "north"}, "control block"),
        ]
        for block_id, properties, message in states:
            with self.subTest(block_id=block_id):
                selection, output = self.write_case(nbt_structure(palette=[(block_id, properties)]))
                with self.assertRaisesRegex(ValueError, message):
                    self.convert(selection, output)

    def test_enforces_container_policy_selection_paths_bounds_and_anchors(self) -> None:
        chest = nbt_structure(
            palette=[("minecraft:chest", {"facing": "north", "type": "single", "waterlogged": "false"})]
        )
        selection, output = self.write_case(chest)
        with self.assertRaisesRegex(ValueError, "containerPolicy"):
            self.convert(selection, output)

        for changes, message in [
            ({"input": {"path": "../room.nbt", "sha256": "0" * 64}}, "relative"),
            ({"bounds": {"min": [0, 0, 0], "max": [4, 1, 0]}}, "bounds"),
            (
                {"anchors": [{"id": "spawn-1", "type": "spawn", "position": [5, 1, 0]}]},
                "anchor",
            ),
        ]:
            selection, output = self.write_case(nbt_structure(), selection_changes=changes)
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                self.convert(selection, output)

        selection, output = self.write_case(
            nbt_structure(),
            selection_changes={
                "anchors": [
                    {"id": "spawn-1", "type": "spawn", "position": [0, 1, 0]},
                    {"id": "goal-1", "type": "goal", "position": [0, 1, 0]},
                ]
            },
        )
        with self.assertRaisesRegex(ValueError, "anchor position"):
            self.convert(selection, output)

    def test_rejects_duplicate_json_keys_and_external_sources_without_locked_evidence(self) -> None:
        selection, output = self.write_case(nbt_structure())
        selection.write_text('{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.convert(selection, output)

        selection, output = self.write_case(nbt_structure(), selection_changes={"sourceKey": "external"})
        ledger = json.loads(self.ledger.read_text(encoding="utf-8"))
        ledger["sources"]["external"] = {
            "origin": "external",
            "bundleEligible": True,
            "licenseStatus": "verified",
            "archive": {"sha256": "1" * 64},
        }
        self.ledger.write_text(json.dumps(ledger), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "archive evidence"):
            self.convert(selection, output)

    def test_external_selection_requires_bounded_curation_notes(self) -> None:
        archive_hash = "1" * 64
        evidence = self.source / "archive-evidence.json"
        evidence.write_text(
            json.dumps({"sourceKey": "external", "sha256": archive_hash}),
            encoding="utf-8",
        )
        ledger = json.loads(self.ledger.read_text(encoding="utf-8"))
        ledger["sources"]["external"] = {
            "origin": "external",
            "bundleEligible": True,
            "licenseStatus": "verified",
            "archive": {"sha256": archive_hash},
        }
        self.ledger.write_text(json.dumps(ledger), encoding="utf-8")
        base = {
            "sourceKey": "external",
            "archiveEvidence": {"path": evidence.name, "sha256": archive_hash},
        }

        for curation in (
            None,
            {"intendedMechanic": "", "cropNotes": "Full template.", "transformationNotes": "DFU only."},
            {"intendedMechanic": "x" * 513, "cropNotes": "Full template.", "transformationNotes": "DFU only."},
            {"intendedMechanic": "Climb", "cropNotes": "Full template."},
        ):
            changes = dict(base)
            if curation is not None:
                changes["curation"] = curation
            selection, output = self.write_case(nbt_structure(), selection_changes=changes)
            with self.subTest(curation=curation), self.assertRaisesRegex(ValueError, "curation"):
                self.convert(selection, output)

        changes = dict(base)
        changes["curation"] = {
            "intendedMechanic": "Climb",
            "cropNotes": "Full template; no crop.",
            "transformationNotes": "Official STRUCTURE DFU only.",
        }
        selection, output = self.write_case(nbt_structure(), selection_changes=changes)
        self.assertEqual("test-room", self.convert(selection, output)["id"])

    def test_map_tool_scripts_are_directly_executable(self) -> None:
        for script in ("inspect_map_source.py", "convert_map_module.py", "verify_map_modules.py"):
            result = subprocess.run(
                [sys.executable, str(REPOSITORY_ROOT / "scripts" / "maps" / script), "--help"],
                cwd=REPOSITORY_ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            with self.subTest(script=script):
                self.assertEqual(0, result.returncode, result.stderr)

    def test_module_verifier_rejects_metadata_policy_anchor_and_bound_drift(self) -> None:
        from scripts.maps.verify_map_modules import verify_module

        selection, output = self.write_case(nbt_structure())
        module = self.convert(selection, output)
        mutations = [
            ("id", lambda value: value.update(id="Bad ID")),
            ("version", lambda value: value.update(version=0)),
            ("sourceKey", lambda value: value.update(sourceKey="../source")),
            ("difficulty", lambda value: value.update(difficulty=6)),
            ("allowedTransforms", lambda value: value.update(allowedTransforms=["rotate_90", "identity"])),
            ("allowedTransforms", lambda value: value.update(allowedTransforms=["identity", "warp"])),
            ("containerPolicy", lambda value: value.update(containerPolicy="all")),
            ("spectatorPolicy", lambda value: value.update(spectatorPolicy="unsafe")),
            ("bounds", lambda value: value.update(bounds={"min": [0, 0, 0], "max": [192, 1, 0]})),
            ("bounds", lambda value: value.update(bounds={"min": [1, 0, 0], "max": [0, 1, 0]})),
            ("bounds", lambda value: value.update(bounds={"min": [0, 0, 0], "max": [1.5, 1, 0]})),
            (
                "anchor position",
                lambda value: value.update(
                    anchors=[
                        {"id": "spawn-1", "type": "spawn", "position": [0, 1, 0]},
                        {"id": "goal-1", "type": "goal", "position": [0, 1, 0]},
                    ]
                ),
            ),
            (
                "anchor",
                lambda value: value.update(
                    anchors=[{"id": "spawn-1", "type": "unknown", "position": [0, 1, 0]}]
                ),
            ),
        ]
        for message, mutate in mutations:
            candidate = json.loads(json.dumps(module))
            mutate(candidate)
            output.write_text(json.dumps(candidate), encoding="utf-8")
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                verify_module(output, self.catalog)


if __name__ == "__main__":
    unittest.main()
