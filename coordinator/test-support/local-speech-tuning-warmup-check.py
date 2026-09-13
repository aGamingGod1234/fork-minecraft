import importlib.util
import json
import os
from pathlib import Path
import sys
import types


class AvailableTts:
    @staticmethod
    def from_pretrained(device):
        return object()


def main():
    variable = sys.argv[1]
    torch = types.ModuleType("torch")
    torch.cuda = types.SimpleNamespace(is_available=lambda: False)
    chatterbox = types.ModuleType("chatterbox")
    chatterbox_tts = types.ModuleType("chatterbox.tts")
    chatterbox_tts.ChatterboxTTS = AvailableTts
    sys.modules["torch"] = torch
    sys.modules["chatterbox"] = chatterbox
    sys.modules["chatterbox.tts"] = chatterbox_tts
    worker_path = Path(__file__).parents[1] / "src" / "voice" / "local-speech-worker.py"
    spec = importlib.util.spec_from_file_location("arena_local_speech_worker", worker_path)
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)
    worker._load_stt = lambda: object()
    os.environ[variable] = "not-a-number"
    print(json.dumps(worker._warmup(), separators=(",", ":")))


if __name__ == "__main__":
    main()
