import base64
import contextlib
import importlib.util
import json
from pathlib import Path
import sys
import threading
import time
import types


class InferenceTracker:
    def __init__(self):
        self._lock = threading.Lock()
        self.active = 0
        self.maximum = 0

    @contextlib.contextmanager
    def operation(self):
        with self._lock:
            self.active += 1
            self.maximum = max(self.maximum, self.active)
        try:
            time.sleep(0.02)
            yield
        finally:
            with self._lock:
                self.active -= 1


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

    def div_(self, _divisor):
        return self

    def to(self, _data_type):
        return self

    def numpy(self):
        return self

    def tobytes(self):
        return b"\x01\x00\x02\x00"

    def __mul__(self, _multiplier):
        return self


class FakeArray:
    def copy(self):
        return self


class FakeTtsModel:
    sr = 24_000

    def __init__(self, tracker):
        self._tracker = tracker

    def generate(self, _text, **_options):
        with self._tracker.operation():
            return FakeTensor()


class FakeSttModel:
    def __init__(self, tracker):
        self._tracker = tracker

    def transcribe(self, _audio, **_options):
        with self._tracker.operation():
            segment = types.SimpleNamespace(text="heard", avg_logprob=-0.1, start=0, end=1)
            return [segment], None


def install_fake_dependencies():
    numpy = types.ModuleType("numpy")
    numpy.frombuffer = lambda _value, dtype=None: FakeArray()

    torch = types.ModuleType("torch")
    torch.int16 = object()
    torch.inference_mode = contextlib.nullcontext
    torch.from_numpy = lambda _value: FakeTensor()

    torchaudio = types.ModuleType("torchaudio")
    audio_functional = types.ModuleType("torchaudio.functional")
    audio_functional.resample = lambda _waveform, _source_rate, _target_rate: FakeTensor()
    torchaudio.functional = audio_functional

    sys.modules["numpy"] = numpy
    sys.modules["torch"] = torch
    sys.modules["torchaudio"] = torchaudio
    sys.modules["torchaudio.functional"] = audio_functional


def load_worker():
    worker_path = Path(__file__).parents[1] / "src" / "voice" / "local-speech-worker.py"
    spec = importlib.util.spec_from_file_location("arena_local_speech_worker", worker_path)
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)
    return worker


def main():
    install_fake_dependencies()
    worker = load_worker()
    tracker = InferenceTracker()
    worker._load_tts = lambda: FakeTtsModel(tracker)
    worker._load_stt = lambda: FakeSttModel(tracker)

    request_count = 8
    start = threading.Barrier(request_count + 1)
    errors = []

    def synthesize():
        try:
            start.wait()
            worker._tts({"text": "hello", "voiceId": "test", "speed": 1})
        except Exception as error:
            errors.append(error)

    def transcribe():
        try:
            start.wait()
            worker._stt({"pcmBase64": base64.b64encode(b"\x01\x00").decode("ascii")})
        except Exception as error:
            errors.append(error)

    threads = [
        threading.Thread(target=synthesize if index % 2 == 0 else transcribe)
        for index in range(request_count)
    ]
    for thread in threads:
        thread.start()
    start.wait()
    for thread in threads:
        thread.join(timeout=5)

    assert not any(thread.is_alive() for thread in threads), "concurrent inference did not finish"
    assert not errors, repr(errors)
    assert tracker.maximum == 1, f"local model inference overlapped {tracker.maximum} calls"

    warmup_tracker = InferenceTracker()
    tts_model = FakeTtsModel(warmup_tracker)
    stt_model = FakeSttModel(warmup_tracker)

    def tracked_loader(attribute, model):
        def load():
            with warmup_tracker.operation():
                setattr(worker, attribute, model)
                return model
        return load

    worker._tts_model = None
    worker._stt_model = None
    worker._load_tts = tracked_loader("_tts_model", tts_model)
    worker._load_stt = tracked_loader("_stt_model", stt_model)
    worker._warmup_started = False
    warmup_start = threading.Barrier(5)
    warmup_errors = []
    warmup_results = []

    def run(operation):
        try:
            warmup_start.wait()
            operation()
        except Exception as error:
            warmup_errors.append(error)

    def warmup():
        warmup_results.append(worker._warmup())

    warmup_threads = [
        threading.Thread(target=run, args=(warmup,)),
        threading.Thread(target=run, args=(warmup,)),
        threading.Thread(target=run, args=(lambda: worker._tts({"text": "hello", "voiceId": "test", "speed": 1}),)),
        threading.Thread(target=run, args=(lambda: worker._stt({"pcmBase64": base64.b64encode(b"\x01\x00").decode("ascii")}),)),
    ]
    for thread in warmup_threads:
        thread.start()
    warmup_start.wait()
    for thread in warmup_threads:
        thread.join(timeout=5)

    assert not any(thread.is_alive() for thread in warmup_threads), "warmup and live inference did not finish"
    assert not warmup_errors, repr(warmup_errors)
    assert warmup_results == [
        {"sttReady": True, "ttsReady": True},
        {"sttReady": True, "ttsReady": True},
    ], repr(warmup_results)
    assert warmup_tracker.maximum == 1, (
        f"warmup overlapped local model work {warmup_tracker.maximum} times"
    )

    worker._warmup_started = False
    worker._tts_model = None
    worker._stt_model = None
    worker._load_stt = tracked_loader("_stt_model", stt_model)

    def fail_tts_warmup():
        raise RuntimeError("Chatterbox unavailable")

    worker._load_tts = fail_tts_warmup
    assert worker._warmup() == {"sttReady": True, "ttsReady": False}

    worker._load_tts = tracked_loader("_tts_model", tts_model)
    assert worker._warmup() == {"sttReady": True, "ttsReady": True}, (
        "a transient channel warmup failure must be retried in the same worker"
    )

    priority_queue = worker._WeightedRequestQueue(maximum_stt_burst=3)
    for value, operation in (
        ("tts-1", "tts"), ("tts-2", "tts"),
        ("stt-1", "stt"), ("stt-2", "stt"), ("stt-3", "stt"), ("stt-4", "stt"),
    ):
        priority_queue.put(value, operation)
    weighted_order = [priority_queue.take() for _ in range(6)]
    priority_queue.close()

    print(json.dumps({
        "requests": request_count,
        "warmupRequests": len(warmup_results),
        "maximumConcurrentInference": tracker.maximum,
        "maximumConcurrentWarmupOrInference": warmup_tracker.maximum,
        "weightedOrder": weighted_order,
    }))


if __name__ == "__main__":
    main()
