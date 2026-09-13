"""Tiny retained evidence fixtures. No Java, process launch, copying or deletion."""
import copy
from datetime import datetime, timezone
from pathlib import Path
import sys
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import package
import runtime_receipt


CHUNK_MARKERS = [f"FORK_RUNTIME_CHUNK_{x}_{z}_OK" for z in range(1906, 1922) for x in range(1857, 1873)]
BLOCK_MARKERS = [f"FORK_RUNTIME_BLOCK_{index}_OK" for index in range(12)]
LOG = '''[Server thread/INFO]: Starting minecraft server version 26.1.2
[Server thread/INFO]: Preparing level "Synthetic runtime check"
[Server thread/INFO]: Done (1.50s)! For help, type "help"
''' + "\n".join("[Server thread/INFO]: [Server] " + marker for marker in CHUNK_MARKERS + BLOCK_MARKERS) + '''
[Server thread/INFO]: Stopping server
[Server thread/INFO]: Saving chunks for level 'ServerLevel[Synthetic runtime check]'/minecraft:overworld
[Server thread/INFO]: ThreadedAnvilChunkStorage: All dimensions are saved
'''


class RuntimeReceiptTests(unittest.TestCase):
    def setUp(self):
        self.root = runtime_receipt.RUNTIME_ROOT / ("synthetic-receipt-tests-" + uuid.uuid4().hex)
        self.source = self.root / "source"
        self.world = self.root / "copied"
        self.source.mkdir(parents=True)
        self.world.mkdir()
        (self.source / "level.dat").write_bytes(b"synthetic source")
        (self.world / "level.dat").write_bytes(b"synthetic post-save")
        self.log = self.root / "server.log"
        self.log.write_text(LOG)
        self.jar = self.root / "synthetic.jar"
        self.jar.write_bytes(b"synthetic jar evidence, not executable")
        self.spec = {"candidate_path": str(self.source), "candidate_before": package.snapshot_tree(self.source),
                     "copied_world_path": str(self.world), "log_path": str(self.log),
                     "expected_level_name": "Synthetic runtime check", "jar_path": str(self.jar),
                     "expected_jar_sha256": package._file_record(self.jar)["sha256"],
                     "java_identity": 'openjdk version "25.0.4.1" SYNTHETIC TEST',
                     "port": 25617, "max_heap_mib": 3072, "minecraft_version": "26.1.2",
                     "expected_chunk_markers": CHUNK_MARKERS,
                     "expected_block_markers": BLOCK_MARKERS,
                     "process": {"pid": 999999, "exit_code": 0, "observed_alive": False,
                                 "observed_at_utc": datetime.now(timezone.utc).isoformat()}}

    def test_positive_receipt_keeps_other_gates_false(self):
        result = runtime_receipt.check_runtime_receipt(self.spec)
        self.assertTrue(result["runtimeLoadAccepted"], result["issues"])
        self.assertFalse(result["visualAccepted"])
        self.assertFalse(result["aiAccepted"])
        self.assertTrue(result["candidateUnchanged"])
        self.assertEqual(1, result["copied_world_file_count"])

    def test_clean_stop_and_dead_pid_accepts_unavailable_exit_code(self):
        self.spec["process"]["exit_code"] = None
        self.assertTrue(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"])
        self.spec["process"]["observed_alive"] = None
        self.assertFalse(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"])

    def test_source_mutation_rejected(self):
        (self.source / "level.dat").write_bytes(b"changed source")
        self.assertFalse(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"])

    def test_hash_only_baseline_and_source_tiles_supported(self):
        hashes = {name: row["sha256"] for name, row in self.spec["candidate_before"]["files"].items()}
        self.spec["candidate_before"] = hashes
        self.spec["input_snapshots"] = [{"path": str(self.source), "files": hashes}]
        result = runtime_receipt.check_runtime_receipt(self.spec)
        self.assertTrue(result["runtimeLoadAccepted"], result["issues"])

    def test_missing_chunk_marker_or_command_echo_does_not_prove_load(self):
        self.log.write_text(LOG.replace("[Server] " + CHUNK_MARKERS[0], "issued command say " + CHUNK_MARKERS[0]))
        result = runtime_receipt.check_runtime_receipt(self.spec)
        self.assertFalse(result["runtimeLoadAccepted"])
        self.assertEqual([CHUNK_MARKERS[0]], result["chunk_load_gate"]["missing"])

    def test_missing_block_sentinel_fails_despite_all_chunks_loaded(self):
        self.log.write_text(LOG.replace("[Server] " + BLOCK_MARKERS[0], "issued command say " + BLOCK_MARKERS[0]))
        result = runtime_receipt.check_runtime_receipt(self.spec)
        self.assertFalse(result["runtimeLoadAccepted"])
        self.assertEqual(256, len(result["chunk_load_gate"]["found"]))
        self.assertEqual([BLOCK_MARKERS[0]], result["block_sentinel_gate"]["missing"])

    def test_too_few_or_duplicate_block_sentinels_rejected(self):
        for markers in (BLOCK_MARKERS[:11], BLOCK_MARKERS[:11] + [BLOCK_MARKERS[0]]):
            self.spec["expected_block_markers"] = markers
            self.assertFalse(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"])

    def test_wrong_target_and_incomplete_shutdown_rejected(self):
        self.spec["expected_level_name"] = "Other world"
        self.assertFalse(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"])
        self.spec["expected_level_name"] = "Synthetic runtime check"
        self.log.write_text(LOG.replace("All dimensions are saved", "saving incomplete"))
        self.assertFalse(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"])

    def test_manual_save_before_stop_does_not_mask_clean_shutdown(self):
        manual_save = ("[Server thread/INFO]: Saving chunks for manual save-all flush\n"
                       "[Server thread/INFO]: ThreadedAnvilChunkStorage: All dimensions are saved\n")
        self.log.write_text(LOG.replace("[Server thread/INFO]: Stopping server", manual_save + "[Server thread/INFO]: Stopping server"))
        result = runtime_receipt.check_runtime_receipt(self.spec)
        self.assertTrue(result["runtimeLoadAccepted"], result["issues"])

    def test_pre_stop_manual_save_cannot_replace_shutdown_save(self):
        before_stop = LOG.replace("[Server thread/INFO]: Stopping server\n", "")
        self.log.write_text(before_stop + "[Server thread/INFO]: Stopping server\n")
        result = runtime_receipt.check_runtime_receipt(self.spec)
        self.assertFalse(result["runtimeLoadAccepted"])
        self.assertFalse(result["log_markers"]["saving_chunks"])

    def test_errors_rejected(self):
        for line in ("[Server thread/ERROR]: Failed", "[Server thread/FATAL]: Failed", "NBT read exception",
                     "Failed to load chunk", "Chunk deserialize error", "DataFixer error"):
            self.log.write_text(LOG + line)
            self.assertFalse(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"], line)

    def test_live_or_failed_process_rejected(self):
        self.spec["process"]["observed_alive"] = True
        self.assertFalse(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"])
        self.spec["process"]["observed_alive"] = False
        self.spec["process"]["exit_code"] = 1
        self.assertFalse(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"])

    def test_wrong_jar_or_heap_rejected(self):
        self.spec["expected_jar_sha256"] = "0" * 64
        self.assertFalse(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"])
        self.spec["expected_jar_sha256"] = package._file_record(self.jar)["sha256"]
        self.spec["max_heap_mib"] = 4096
        self.assertFalse(runtime_receipt.check_runtime_receipt(self.spec)["runtimeLoadAccepted"])

    def test_receipt_cannot_be_written_into_world(self):
        with self.assertRaises(package.GateError):
            runtime_receipt.write_receipt(self.spec, self.source / "receipt.json")
        result = runtime_receipt.write_receipt(self.spec, self.root / "receipt.json")
        self.assertTrue(result["runtimeLoadAccepted"])
        self.assertTrue((self.root / "receipt.json").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
