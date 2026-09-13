"""Synthetic adapter tests only; no Minecraft process or existing save is touched."""
from collections import defaultdict
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import sys
import unittest
from unittest import mock
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import anvil
import grow_runtime
import overlay
import package


class GrowRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.root = package.OUTPUT_ROOT / ("synthetic-grt-" + uuid.uuid4().hex[:12])
        self.source = self.root / "source"
        self.attempt = self.root / "runtime"
        (self.source / "dimensions/minecraft/overworld/region").mkdir(parents=True)
        (self.attempt / "world").mkdir(parents=True)
        (self.attempt / "world/level.dat").write_bytes(b"synthetic post-runtime placeholder")
        self.override = mock.patch.object(grow_runtime, "RUNTIME_BINDINGS", {"synthetic": (self.source, self.attempt)})
        self.override.start()
        self.addCleanup(self.override.stop)
        rows = [{"x": 2, "z": 2, "yMin": 1, "yMax": 3, "block": "minecraft:stone", "layer": "building",
                 "featureId": "synthetic-building", "geometryKind": "wall", "sourceClass": "SYNTHETIC"},
                {"x": 12, "z": 2, "yMin": 0, "yMax": 1, "block": "minecraft:stone", "layer": "road",
                 "featureId": "synthetic-road", "geometryKind": "road", "sourceClass": "SYNTHETIC"}]
        columns = defaultdict(list)
        for row in rows:
            run = overlay.parse_run(row, [0, 0, 16, 16])
            columns[(run.x, run.z)].append(run)
        chunk = overlay.make_chunk(0, 0, columns, 4790, {"crossLayerOverwrittenBlocks": defaultdict(int)})
        anvil.write_region(self.source / "dimensions/minecraft/overworld/region/r.0.0.mca", {(0, 0): chunk})
        compound = lambda value: anvil.Tag(10, value)
        level = anvil.NbtFile("", compound({"Data": compound({"DataVersion": anvil.Tag(3, 4790),
            "spawn": compound({"pos": anvil.Tag(11, [1, 1, 1]), "dimension": anvil.Tag(8, "minecraft:overworld")})})}))
        anvil.write_level_dat(self.source / "level.dat", level)
        self.sentinels = [{"id": "building", "kind": "building", "x": 2, "y": 1, "z": 2, "block": "minecraft:stone"},
                          {"id": "road", "kind": "road", "x": 12, "y": 0, "z": 2, "block": "minecraft:stone"},
                          {"id": "terrain_sw", "kind": "terrain", "x": 2, "y": 0, "z": 12, "block": "minecraft:grass_block"},
                          {"id": "terrain_se", "kind": "terrain", "x": 12, "y": 0, "z": 12, "block": "minecraft:grass_block"}]

    def plan(self):
        return grow_runtime.prepare_runtime_plan(self.source, [0, 0, 16, 16], self.sentinels, attempt_root=self.attempt)

    def evidence(self, plan):
        definitions = self.attempt / "sentinels.json"
        definitions.write_text(json.dumps(plan["sentinels"]))
        commands = plan["forceload_commands"] + plan["chunk_commands"] + plan["block_commands"] + plan["finish_commands"]
        transcript = self.attempt / "stdin.txt"
        recorder = grow_runtime.StdinTranscript(transcript, commands)
        fake_stdin = io.BytesIO()
        for command in commands:
            recorder.send(fake_stdin, command)
        closed = recorder.close()
        self.assertEqual(fake_stdin.getvalue(), transcript.read_bytes())
        self.assertEqual(package._file_record(transcript), closed)
        markers = plan["expected_chunk_markers"] + [item["marker"] for item in plan["sentinels"]]
        log = self.attempt / "console.log"
        log.write_text('Starting minecraft server version 26.1.2\nPreparing level "world"\nDone (1.0s)!\n'
                       + "".join("[Server thread/INFO]: [Server] " + marker + "\n" for marker in markers)
                       + "Stopping server\nSaving chunks for level world\nAll dimensions are saved\n")
        jar = self.attempt / "server.jar"
        jar.write_bytes(b"synthetic not-executable jar")
        spec = {"copied_world_path": str(self.attempt / "world"), "sentinel_definitions_path": str(definitions),
                "log_path": str(log), "max_heap_mib": 3072, "active_processor_count": 1, "cpu_affinity_mask": 256,
                "free_memory_before_bytes": 9 * 1024 ** 3, "minimum_free_memory_bytes": 8 * 1024 ** 3,
                "jar_path": str(jar), "expected_jar_sha256": package._file_record(jar)["sha256"],
                "minecraft_version": "26.1.2", "java_identity": "SYNTHETIC Java25",
                "process": {"pid": 999999, "exit_code": 0, "observed_alive": False,
                            "observed_at_utc": datetime.now(timezone.utc).isoformat()}}
        return spec, transcript

    def test_plan_loads_selected_chunks_only_and_adds_safe_spawn(self):
        plan = self.plan()
        self.assertEqual([[0, 0]], plan["selected_chunks"])
        self.assertEqual(["forceload add 8 8"], plan["forceload_commands"])
        self.assertEqual(7, len(plan["sentinels"]))
        self.assertEqual({"spawn"}, {item["kind"] for item in plan["sentinels"][-3:]})
        self.assertEqual(1, plan["crop_chunk_count"])
        self.assertIn("1-chunk crop", plan["runtime_scope"])
        self.assertNotIn("4096", plan["runtime_scope"])

    def test_candidate_cannot_use_another_district_attempt(self):
        with mock.patch.object(grow_runtime, "RUNTIME_BINDINGS", {
                "first": (self.source, self.attempt),
                "second": (self.root / "other-source", self.root / "other-attempt")}):
            with self.assertRaisesRegex(ValueError, "approved district binding"):
                grow_runtime.prepare_runtime_plan(self.source, [0, 0, 16, 16], self.sentinels,
                                                  attempt_root=self.root / "other-attempt")

    def test_only_six_explicit_production_bindings(self):
        self.override.stop()
        try:
            self.assertEqual({"lim-chu-kang-v1", "changi-v1", "cbd-east-v1", "cbd-east-v2", "cbd-east-ring-v1", "cbd-south-v1"}, set(grow_runtime.RUNTIME_BINDINGS))
            for district, (source, attempt) in grow_runtime.RUNTIME_BINDINGS.items():
                self.assertEqual(package.OUTPUT_ROOT / ("grow-" + district) / "world", source)
                self.assertEqual(package.OUTPUT_ROOT.parent / "runtime-check" / district, attempt)
                self.assertEqual(district, grow_runtime._district_binding(source, attempt))
        finally:
            self.override.start()

    def test_ring_launcher_and_runtime_paths_are_exactly_bound(self):
        import grow_runtime_run
        district = "cbd-east-ring-v1"
        self.assertEqual(("grow-cbd-east-ring-v1", "FORK-CBD-East-Ring-v1", "Singapore CBD and surrounding district"),
                         grow_runtime_run.DISTRICTS[district])
        self.override.stop()
        try:
            source = package.OUTPUT_ROOT / "grow-cbd-east-ring-v1" / "world"
            attempt = package.OUTPUT_ROOT.parent / "runtime-check" / district
            self.assertEqual(district, grow_runtime._district_binding(source, attempt))
            with self.assertRaisesRegex(ValueError, "approved district binding"):
                grow_runtime._district_binding(source, attempt.parent / "cbd-east-v2")
            with self.assertRaisesRegex(ValueError, "approved district binding"):
                grow_runtime._district_binding(package.OUTPUT_ROOT / "grow-cbd-east-ring-v1-copy" / "world", attempt)
        finally:
            self.override.start()

    def test_cbd_south_package_binding_rejects_other_attempts(self):
        import grow_runtime_run
        self.assertEqual(("grow-cbd-south-v1", "FORK-Singapore-CBD-South-1792x2048",
                          "Singapore CBD and adjoining south district"),
                         grow_runtime_run.DISTRICTS["cbd-south-v1"])
        self.override.stop()
        try:
            source = package.OUTPUT_ROOT / "grow-cbd-south-v1" / "world"
            runtime = package.OUTPUT_ROOT.parent / "runtime-check" / "cbd-south-v1"
            self.assertEqual("cbd-south-v1", grow_runtime._district_binding(source, runtime))
            for other in (runtime.parent / "cbd-east-v2", runtime.parent / "cbd-south-v2", runtime / "extra"):
                with self.subTest(other=other), self.assertRaisesRegex(ValueError, "approved district binding"):
                    grow_runtime._district_binding(source, other)
        finally:
            self.override.start()

    def test_false_source_sentinel_rejected(self):
        self.sentinels[0]["block"] = "minecraft:glass"
        with self.assertRaisesRegex(ValueError, "differs from supplied"):
            self.plan()

    def test_missing_kind_or_quadrant_rejected(self):
        self.sentinels.pop()
        with self.assertRaisesRegex(ValueError, "four crop quadrants"):
            self.plan()

    def test_complete_representative_receipt_is_not_full_world_or_visual_acceptance(self):
        plan = self.plan()
        spec, transcript = self.evidence(plan)
        result = grow_runtime.verify_grow_runtime(spec, plan, transcript)
        self.assertTrue(result["runtimeLoadAccepted"], result["issues"])
        self.assertFalse(result["allOwnedChunksRuntimeTested"])
        self.assertFalse(result["visualAccepted"])
        self.assertFalse(result["aiAccepted"])

    def test_missing_actual_marker_rejected(self):
        plan = self.plan()
        spec, transcript = self.evidence(plan)
        log = Path(spec["log_path"])
        log.write_text(log.read_text().replace("[Server] " + plan["sentinels"][0]["marker"], "command echo"))
        self.assertFalse(grow_runtime.verify_grow_runtime(spec, plan, transcript)["runtimeLoadAccepted"])

    def test_transcript_extra_command_and_wrong_memory_floor_rejected(self):
        plan = self.plan()
        spec, transcript = self.evidence(plan)
        spec["minimum_free_memory_bytes"] = 7 * 1024 ** 3
        self.assertFalse(grow_runtime.verify_grow_runtime(spec, plan, transcript)["runtimeLoadAccepted"])
        spec["minimum_free_memory_bytes"] = 8 * 1024 ** 3
        with transcript.open("ab") as stream:
            stream.write(b"forceload add 0 0 1023 1023\n")
        self.assertFalse(grow_runtime.verify_grow_runtime(spec, plan, transcript)["runtimeLoadAccepted"])

    def test_recorder_rejects_unplanned_commands(self):
        recorder = grow_runtime.StdinTranscript(self.attempt / "rejected.txt", ["stop"])
        try:
            with self.assertRaises(ValueError):
                recorder.send(io.BytesIO(), "op somebody")
        finally:
            recorder.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
