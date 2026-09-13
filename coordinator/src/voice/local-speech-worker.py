import base64
import collections
import contextlib
import hashlib
import json
import math
import os
import sys
import threading


_RPC_STDOUT = sys.stdout

MAX_TTS_CODE_POINTS = 280
MAX_TTS_PCM_BYTES = 24_000 * 2 * 20
MAX_STT_PCM_BYTES = 48_000 * 2 * 20

_tts_model = None
_stt_model = None
# Chatterbox and Whisper can share one GPU. Hold this lock across model loading
# and inference even when the RPC worker handles several requests concurrently.
_model_lock = threading.RLock()
_warmup_lock = threading.Lock()
_response_lock = threading.Lock()


class _WeightedRequestQueue:
    """Serializes GPU work while letting live recognition pass queued synthesis."""

    def __init__(self, maximum_stt_burst=3):
        self._condition = threading.Condition()
        self._stt = collections.deque()
        self._tts = collections.deque()
        self._control = collections.deque()
        self._maximum_stt_burst = maximum_stt_burst
        self._stt_burst = 0
        self._closed = False

    def put(self, line, operation):
        with self._condition:
            if self._closed:
                return False
            target = self._stt if operation == "stt" else self._tts if operation == "tts" else self._control
            target.append(line)
            self._condition.notify()
            return True

    def take(self):
        with self._condition:
            while not self._closed and not (self._stt or self._tts or self._control):
                self._condition.wait()
            if not (self._stt or self._tts or self._control):
                return None
            if self._stt and (self._stt_burst < self._maximum_stt_burst or not self._tts):
                self._stt_burst += 1
                return self._stt.popleft()
            if self._tts:
                self._stt_burst = 0
                return self._tts.popleft()
            self._stt_burst = 0
            return self._control.popleft()

    def close(self):
        with self._condition:
            self._closed = True
            self._condition.notify_all()

def _load_tts():
    global _tts_model
    with _model_lock:
        _configured_tts_tuning()
        if _tts_model is not None:
            return _tts_model
        with contextlib.redirect_stdout(sys.stderr):
            import torch
            from chatterbox.tts import ChatterboxTTS

            requested_device = os.environ.get("ARENA_LOCAL_TTS_DEVICE", "").strip().lower()
            device = requested_device or ("cuda" if torch.cuda.is_available() else "cpu")
            _tts_model = ChatterboxTTS.from_pretrained(device=device)
    return _tts_model


def _load_stt():
    global _stt_model
    with _model_lock:
        if _stt_model is not None:
            return _stt_model
        with contextlib.redirect_stdout(sys.stderr):
            import torch
            from faster_whisper import WhisperModel

            model_name = os.environ.get("ARENA_LOCAL_STT_MODEL", "small.en").strip() or "small.en"
            requested_device = os.environ.get("ARENA_LOCAL_STT_DEVICE", "auto").strip().lower() or "auto"
            if requested_device not in {"auto", "cpu", "cuda"}:
                raise ValueError("ARENA_LOCAL_STT_DEVICE must be auto, cpu, or cuda")
            devices = ["cuda", "cpu"] if requested_device == "auto" and torch.cuda.is_available() else [
                "cpu" if requested_device == "auto" else requested_device
            ]
            configured_compute = os.environ.get("ARENA_LOCAL_STT_COMPUTE_TYPE", "").strip().lower()
            last_error = None
            for device in devices:
                compute_type = configured_compute or ("float16" if device == "cuda" else "int8")
                try:
                    _stt_model = WhisperModel(model_name, device=device, compute_type=compute_type)
                    break
                except Exception as error:
                    last_error = error
                    if requested_device != "auto" or device == devices[-1]:
                        raise
            if _stt_model is None and last_error is not None:
                raise last_error
    return _stt_model


def _warmup_model(name, loader):
    try:
        with _model_lock:
            loader()
        return True
    except Exception as error:
        sys.stderr.write(f"Arena local speech {name} warmup failed: {type(error).__name__}: {error}\n")
        sys.stderr.flush()
        return False


def _warmup():
    with _warmup_lock:
        results = {
            "stt": _stt_model is not None or _warmup_model("STT", _load_stt),
            "tts": _tts_model is not None or _warmup_model("TTS", _load_tts),
        }
        return {"sttReady": results["stt"], "ttsReady": results["tts"]}


def _tts(request):
    text = request.get("text")
    voice_id = request.get("voiceId", "local.default.v1")
    speed = request.get("speed", 1)
    tone = request.get("tone", "neutral")
    if not isinstance(text, str) or not text.strip() or len(text) > MAX_TTS_CODE_POINTS:
        raise ValueError("TTS text must contain 1 to 280 code points")
    if not isinstance(voice_id, str) or not voice_id.strip():
        raise ValueError("TTS voice ID is invalid")
    if not isinstance(speed, (int, float)) or not math.isfinite(speed) or speed < 0.5 or speed > 2:
        raise ValueError("TTS speed is invalid")
    if not isinstance(tone, str) or not tone.strip() or len(tone) > 32 or not tone.replace("_", "").replace("-", "").isalnum():
        raise ValueError("TTS tone is invalid")
    conditioning = _voice_conditioning(voice_id, tone)
    with _model_lock:
        model = _load_tts()
        with contextlib.redirect_stdout(sys.stderr):
            import torch

            with torch.inference_mode():
                waveform = model.generate(text, **conditioning)
                waveform = _apply_speed(waveform, speed, torch)
            pcm = (waveform.detach().float().cpu().flatten().clamp(-1, 1) * 32767).to(torch.int16).numpy().tobytes()
    if not pcm or len(pcm) % 2 or len(pcm) > MAX_TTS_PCM_BYTES:
        raise RuntimeError("Generated speech exceeded the 20 second PCM limit")
    return {
        "sampleRateHz": int(model.sr),
        "pcmBase64": base64.b64encode(pcm).decode("ascii"),
        "provider": "local-chatterbox",
        "voiceId": _local_voice_id(voice_id),
    }


def _voice_conditioning(voice_id, tone="neutral"):
    digest = hashlib.sha256(voice_id.encode("utf-8")).digest()
    expression = int.from_bytes(digest[:2], "big") / 0xFFFF
    variation = int.from_bytes(digest[2:4], "big") / 0xFFFF
    exaggeration = round(0.50 + (0.30 * expression), 4)
    cfg_weight = round(0.50 - (0.20 * expression), 4)
    temperature = round(0.78 + (0.04 * variation), 4)
    configured = _configured_tts_tuning()
    exaggeration = configured.get("exaggeration", exaggeration)
    cfg_weight = configured.get("cfg_weight", cfg_weight)
    # Keep voice identity stable while making cinematic cues easy to direct.
    # These values are deliberately bounded because Chatterbox can become
    # unstable when conditioning is pushed too far.
    tone_adjustments = {
        "neutral": (0.00, 0.00, 0.00),
        "warm": (0.05, -0.03, -0.02),
        "excited": (0.18, -0.08, 0.04),
        "serious": (-0.08, 0.08, -0.03),
        "dramatic": (0.14, 0.04, 0.05),
        "whisper": (-0.12, 0.10, -0.04),
        "robotic": (-0.16, 0.16, -0.06),
        "angry": (0.20, 0.02, 0.06),
    }
    exaggeration_delta, cfg_delta, temperature_delta = tone_adjustments.get(tone.lower(), (0.00, 0.00, 0.00))
    exaggeration = max(0.30, min(1.00, exaggeration + exaggeration_delta))
    cfg_weight = max(0.10, min(0.90, cfg_weight + cfg_delta))
    temperature = max(0.50, min(1.20, temperature + temperature_delta))
    return {
        "exaggeration": exaggeration,
        "cfg_weight": cfg_weight,
        "temperature": temperature,
    }


def _configured_tts_tuning():
    configured = {}
    for variable, option in (
        ("ARENA_LOCAL_TTS_EXAGGERATION", "exaggeration"),
        ("ARENA_LOCAL_TTS_CFG_WEIGHT", "cfg_weight"),
    ):
        value = os.environ.get(variable)
        if not value:
            continue
        parsed = float(value)
        if not math.isfinite(parsed):
            raise ValueError(f"{variable} must be finite")
        configured[option] = parsed
    return configured


def _local_voice_id(voice_id):
    digest = hashlib.sha256(voice_id.encode("utf-8")).hexdigest()[:8]
    return f"local.chatterbox.v1.{digest}"


def _apply_speed(waveform, speed, torch):
    if speed == 1:
        return waveform
    flattened = waveform.detach().float().flatten()
    target_length = max(1, round(flattened.shape[0] / speed))
    if target_length == flattened.shape[0]:
        return flattened
    import torch.nn.functional as functional
    return functional.interpolate(
        flattened.view(1, 1, -1), size=target_length, mode="linear", align_corners=False,
    ).flatten()


def _stt(request):
    encoded = request.get("pcmBase64")
    if not isinstance(encoded, str):
        raise ValueError("STT audio is missing")
    try:
        pcm = base64.b64decode(encoded, validate=True)
    except Exception as error:
        raise ValueError("STT audio is invalid") from error
    if not pcm or len(pcm) % 2 or len(pcm) > MAX_STT_PCM_BYTES:
        raise ValueError("STT audio must be at most 20 seconds of 48 kHz mono PCM")
    with _model_lock:
        with contextlib.redirect_stdout(sys.stderr):
            import numpy as np
            import torch
            import torchaudio.functional as audio_functional

            waveform = torch.from_numpy(np.frombuffer(pcm, dtype="<i2").copy()).float().div_(32768.0)
            audio_16khz = audio_functional.resample(waveform, 48_000, 16_000).numpy()
            segments, _ = _load_stt().transcribe(
                audio_16khz,
                language="en",
                beam_size=1,
                vad_filter=True,
                condition_on_previous_text=False,
            )
            completed = list(segments)
    transcript = " ".join(segment.text.strip() for segment in completed if segment.text.strip()).strip()
    if not completed:
        confidence = 0.0
    else:
        weighted_log_probability = sum(segment.avg_logprob * max(1, segment.end - segment.start) for segment in completed)
        duration = sum(max(1, segment.end - segment.start) for segment in completed)
        confidence = max(0.0, min(1.0, math.exp(weighted_log_probability / duration)))
    return {"transcript": transcript[:512], "confidence": confidence}


def _error_code(operation, error):
    if isinstance(error, ValueError):
        return "TTS_INVALID_REQUEST" if operation == "tts" else "STT_MALFORMED_AUDIO"
    return "LOCAL_TTS_ERROR" if operation == "tts" else "LOCAL_STT_ERROR"


def _respond(value):
    with _response_lock:
        _RPC_STDOUT.write(json.dumps(value, separators=(",", ":")) + "\n")
        _RPC_STDOUT.flush()


def _handle_request(line):
    operation = "unknown"
    request_id = None
    try:
        request = json.loads(line)
        request_id = request.get("id")
        operation = request.get("op")
        if not isinstance(request_id, int) or request_id < 1:
            raise ValueError("Request ID is invalid")
        if operation == "tts":
            result = _tts(request)
        elif operation == "stt":
            result = _stt(request)
        elif operation == "warmup":
            result = _warmup()
        else:
            raise ValueError("Speech operation is invalid")
        _respond({"id": request_id, "ok": True, **result})
    except Exception as error:
        message = f"{type(error).__name__}: {error}"[:256]
        _respond({"id": request_id, "ok": False, "code": _error_code(operation, error), "message": message})


def _operation_for_line(line):
    try:
        request = json.loads(line)
        operation = request.get("op")
        return operation if operation in {"stt", "tts", "warmup"} else "control"
    except Exception:
        return "control"


def main():
    requests = _WeightedRequestQueue()
    dispatcher = threading.Thread(target=_dispatch_requests, args=(requests,), daemon=True)
    dispatcher.start()
    try:
        for line in sys.stdin:
            requests.put(line, _operation_for_line(line))
    finally:
        requests.close()
        dispatcher.join(timeout=1)


def _dispatch_requests(requests):
    while True:
        line = requests.take()
        if line is None:
            return
        _handle_request(line)


if __name__ == "__main__":
    main()
