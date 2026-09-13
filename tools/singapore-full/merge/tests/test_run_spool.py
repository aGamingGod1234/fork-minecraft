import json
from contextlib import closing
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_spool import RunSpool


class RunSpoolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_negative_chunk_keys_unicode_and_boundaries(self):
        with RunSpool(self.root / "runs.db") as spool:
            spool.add(-1, -32, {"x": -1, "z": -512, "featureId": "加冷 🏙", "block": "minecraft:stone"})
            spool.add(0, -32, {"x": 0, "z": -512})
            spool.finish()
            self.assertEqual(spool.count, 2)
            self.assertEqual(next(spool.iter_chunk(-1, -32))["featureId"], "加冷 🏙")
            self.assertEqual(list(spool.iter_chunk(-1, -31)), [])
            self.assertEqual(list(spool.iter_chunk(0, -32)), [{"x": 0, "z": -512}])

    def test_input_and_dictionary_order_do_not_change_iteration(self):
        runs = [{"x": 1, "z": 2, "yMin": y, "yMax": y + 1,
                 "featureId": str(y), "properties": {"b": "2", "a": "1"}}
                for y in (9, 1, 30, -4)]
        runs.append(runs[0].copy())
        outputs = []
        for i, sequence in enumerate((runs, list(reversed(runs)))):
            with RunSpool(self.root / f"runs{i}.db") as spool:
                for run in sequence:
                    spool.add(0, 0, dict(reversed(list(run.items()))))
                spool.finish()
                outputs.append(list(spool.iter_chunk(0, 0)))
        self.assertEqual(outputs[0], outputs[1])
        self.assertEqual(len(outputs[0]), len(runs))
        encoded = [json.dumps(r, sort_keys=True, ensure_ascii=False, separators=(",", ":")) for r in outputs[0]]
        self.assertEqual(encoded, sorted(encoded))

    def test_readonly_reopen_cannot_mutate_and_does_not_change_file(self):
        path = self.root / "runs.db"
        with RunSpool(path) as spool:
            spool.add(2, 3, {"x": 32})
        before = path.read_bytes()
        with RunSpool(path, create=False) as spool:
            self.assertEqual(spool.count, 1)
            self.assertEqual(list(spool.iter_chunk(2, 3)), [{"x": 32}])
            spool.finish()
            with self.assertRaises(PermissionError):
                spool.add(2, 3, {})
        self.assertEqual(path.read_bytes(), before)

    def test_existing_path_is_never_overwritten(self):
        path = self.root / "existing.db"
        path.write_bytes(b"existing private data")
        with self.assertRaises(FileExistsError):
            RunSpool(path)
        self.assertEqual(path.read_bytes(), b"existing private data")
        with self.assertRaises(sqlite3.OperationalError):
            RunSpool(self.root / "missing.db", create=False)
        self.assertFalse((self.root / "missing.db").exists())

    def test_batches_commit_1000_and_final_partial_batch_is_sealed(self):
        path = self.root / "runs.db"
        spool = RunSpool(path)
        for i in range(1001):
            spool.add(i // 16, 0, {"x": i})
        with closing(sqlite3.connect(path)) as external:
            self.assertEqual(external.execute("SELECT COUNT(*) FROM runs").fetchone()[0], 1000)
            self.assertEqual(external.execute("SELECT finished FROM metadata").fetchone()[0], 0)
        spool.close()
        spool.close()
        with RunSpool(path, create=False) as reopened:
            self.assertEqual(reopened.count, 1001)
            self.assertEqual(list(reopened.iter_chunk(62, 0)), [{"x": x} for x in range(1000, 1001)] + [{"x": x} for x in range(992, 1000)])
        with closing(sqlite3.connect(path)) as external:
            self.assertEqual(external.execute("SELECT COUNT(*) FROM runs").fetchone()[0], 1001)

    def test_exception_leaves_incomplete_spool_unaccepted(self):
        path = self.root / "runs.db"
        with self.assertRaisesRegex(RuntimeError, "producer failed"):
            with RunSpool(path) as spool:
                spool.add(0, 0, {"x": 0})
                raise RuntimeError("producer failed")
        with self.assertRaisesRegex(ValueError, "not a completed"):
            RunSpool(path, create=False)

    def test_stream_and_seal_contract(self):
        with RunSpool(self.root / "runs.db") as spool:
            spool.add(0, 0, {"sourceClass": "x' OR 1=1; --"})
            with self.assertRaises(RuntimeError):
                list(spool.iter_chunk(0, 0))
            spool.finish()
            spool.finish()
            stream = spool.iter_chunk(0, 0)
            self.assertIs(iter(stream), stream)
            self.assertEqual(next(stream), {"sourceClass": "x' OR 1=1; --"})
            self.assertEqual(list(stream), [])
            with self.assertRaises(RuntimeError):
                spool.add(0, 0, {})

    def test_bounded_sqlite_settings_and_index(self):
        with RunSpool(self.root / "runs.db") as spool:
            connection = spool._open_connection()
            self.assertEqual(connection.execute("PRAGMA cache_size").fetchone()[0], -16384)
            self.assertEqual(connection.execute("PRAGMA temp_store").fetchone()[0], 1)
            self.assertEqual(connection.execute("PRAGMA mmap_size").fetchone()[0], 0)
            self.assertEqual([row[2] for row in connection.execute("PRAGMA index_info(runs_chunk)")], ["cx", "cz"])

    def test_failed_binding_does_not_leave_hidden_transaction(self):
        with RunSpool(self.root / "runs.db") as spool:
            with self.assertRaises(OverflowError):
                spool.add(1 << 100, 0, {})
            spool.add(0, 0, {"x": 0})
            spool.finish()
            self.assertEqual(spool.count, 1)
            self.assertEqual(list(spool.iter_chunk(0, 0)), [{"x": 0}])


if __name__ == "__main__":
    unittest.main()
