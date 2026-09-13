import contextlib
import importlib.util
import json
from pathlib import Path
import sys
import types


class FakeTensor:
    def detach(self):
        return self

    def float(self):
        return self

    def cpu(self):
        return self

    def flatten(self):
        return self

    def clamp(self, _minimum, _maximum):
        return self

    def to(self, _data_type):
        return self

    def numpy(self):
        return self

    def tobytes(self):
        return b"\x01\x00\x02\x00"

    def __mul__(self, _multiplier):
        return self


class CapturingTtsModel:
    sr = 24_000

    def __init__(self):
        self.conditioning = []

    def generate(self, _text, **options):
        self.conditioning.append(options)
        return FakeTensor()


def load_worker():
    worker_path = Path(__file__).parents[1] / "src" / "voice" / "local-speech-worker.py"
    spec = importlib.util.spec_from_file_location("arena_local_speech_worker", worker_path)
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)
    return worker


def main():
    voice_ids = json.loads(sys.argv[1])
    assert isinstance(voice_ids, list) and len(voice_ids) == 16
    assert len(set(voice_ids)) == 16

    torch = types.ModuleType("torch")
    torch.int16 = object()
    torch.inference_mode = contextlib.nullcontext
    sys.modules["torch"] = torch

    worker = load_worker()
    model = CapturingTtsModel()
    worker._load_tts = lambda: model
    for voice_id in voice_ids:
        worker._tts({"text": "voice identity check", "voiceId": voice_id, "speed": 1})

    print(json.dumps(model.conditioning, separators=(",", ":")))


if __name__ == "__main__":
    main()
