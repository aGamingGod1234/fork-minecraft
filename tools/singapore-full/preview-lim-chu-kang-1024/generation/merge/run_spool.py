"""Bounded, deterministic disk spool for already validated global block runs."""
from __future__ import annotations

import json
from pathlib import Path
import sqlite3


class RunSpool:
    """Append validated JSON runs, seal, then stream one chunk at a time.

    Creating a spool never replaces a file. A clean context exit (or close)
    seals outstanding writes. An exceptional exit rolls back the last batch
    and leaves an unsealed diagnostic database, which cannot be reopened for
    consumption. No run geometry or block-state semantics are checked here.
    """

    BATCH_SIZE = 1000
    CACHE_KIB = 16 * 1024
    SCHEMA_VERSION = 1

    def __init__(self, path, create=True):
        self.path = Path(path).resolve()
        self._connection = None
        self._writable = bool(create)
        self._finished = False
        self._pending = 0
        self._count = 0
        if create:
            # Reserve exactly this new attempt file before SQLite can open it.
            # The caller owns scratch directory placement and lifecycle.
            with self.path.open("xb"):
                pass
        try:
            self._connection = sqlite3.connect(
                self.path.as_uri() + ("?mode=rw" if create else "?mode=ro"),
                uri=True, isolation_level=None,
            )
            connection = self._connection
            connection.execute(f"PRAGMA cache_size=-{self.CACHE_KIB}")
            connection.execute("PRAGMA temp_store=FILE")
            connection.execute("PRAGMA mmap_size=0")
            if create:
                connection.execute("PRAGMA journal_mode=DELETE")
                connection.execute("PRAGMA synchronous=NORMAL")
                connection.execute("BEGIN")
                connection.execute("CREATE TABLE metadata (version INTEGER NOT NULL, finished INTEGER NOT NULL, run_count INTEGER NOT NULL)")
                connection.execute("INSERT INTO metadata VALUES (?, 0, 0)", (self.SCHEMA_VERSION,))
                connection.execute("CREATE TABLE runs (cx INTEGER NOT NULL, cz INTEGER NOT NULL, payload TEXT NOT NULL)")
                connection.execute("CREATE INDEX runs_chunk ON runs (cx, cz)")
                connection.commit()
            else:
                row = connection.execute("SELECT version, finished, run_count FROM metadata").fetchall()
                if (len(row) != 1 or row[0][0] != self.SCHEMA_VERSION or row[0][1] != 1
                        or type(row[0][2]) is not int or row[0][2] < 0):
                    raise ValueError("spool is not a completed supported run database")
                self._count = row[0][2]
                self._finished = True
        except BaseException:
            if self._connection is not None:
                self._connection.close()
                self._connection = None
            raise

    def _open_connection(self):
        if self._connection is None:
            raise RuntimeError("spool is closed")
        return self._connection

    @property
    def count(self):
        return self._count

    def add(self, cx, cz, payload: dict):
        connection = self._open_connection()
        if not self._writable:
            raise PermissionError("spool was opened read-only")
        if self._finished:
            raise RuntimeError("spool is sealed")
        if type(cx) is not int or type(cz) is not int:
            raise TypeError("chunk keys must be integers")
        if not isinstance(payload, dict):
            raise TypeError("serialized run payload must be a dictionary")
        encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True,
                             separators=(",", ":"), allow_nan=False)
        started_transaction = self._pending == 0
        if started_transaction:
            connection.execute("BEGIN")
        try:
            connection.execute("INSERT INTO runs (cx, cz, payload) VALUES (?, ?, ?)",
                               (cx, cz, encoded))
        except BaseException:
            if started_transaction:
                connection.rollback()
            raise
        self._pending += 1
        self._count += 1
        if self._pending == self.BATCH_SIZE:
            connection.commit()
            self._pending = 0

    def finish(self):
        connection = self._open_connection()
        if self._finished:
            return
        if self._pending:
            connection.commit()
            self._pending = 0
        connection.execute("BEGIN")
        connection.execute("UPDATE metadata SET finished=1, run_count=?", (self._count,))
        connection.commit()
        self._finished = True

    def iter_chunk(self, cx, cz):
        """Yield decoded payloads individually; SQLite sorts using its file temp store."""
        connection = self._open_connection()
        if not self._finished:
            raise RuntimeError("finish the spool before reading chunks")
        if type(cx) is not int or type(cz) is not int:
            raise TypeError("chunk keys must be integers")
        cursor = connection.execute(
            "SELECT payload FROM runs WHERE cx=? AND cz=? ORDER BY payload COLLATE BINARY",
            (cx, cz),
        )
        try:
            for (encoded,) in cursor:
                yield json.loads(encoded)
        finally:
            cursor.close()

    def close(self):
        if self._connection is None:
            return
        try:
            if self._writable and not self._finished:
                self.finish()
        finally:
            self._connection.close()
            self._connection = None

    def __enter__(self):
        self._open_connection()
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        if exc_type is None:
            self.close()
        elif self._connection is not None:
            try:
                self._connection.rollback()
            finally:
                self._connection.close()
                self._connection = None
        return False
