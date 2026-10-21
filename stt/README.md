# stt — local speech-to-text service (Java · sherpa-onnx + Whisper)

Standalone HTTP service that transcribes audio **on this host**: English + Tamil, three
selectable Whisper sizes (`small` / `medium` / `large-v3`, all int8), inference via the
sherpa-onnx JNI bindings. It backs the voice-chat page and has no Python dependency — the
retired `faster-whisper` sidecar is gone.

## Files

Committed:

| File | What |
| --- | --- |
| `SttServer.java` | the service — JDK 21, `com.sun.net.httpserver`, default package |
| `silero_vad.onnx` | optional voice-activity model (`-Dstt.vad=true` to enable) |
| `README.md` | this file |

Downloaded, **not** committed (see `.gitignore`):

| File | Where it comes from |
| --- | --- |
| `lib/sherpa-onnx-jvm.jar` | sherpa-onnx JVM bindings (JitPack), v1.13.8 |
| `lib/libsherpa-onnx-jni.so`, `lib/libonnxruntime.so` | the sherpa-onnx / onnxruntime natives for your platform (x86-64 Linux here) |
| `models/<name>/` | installed at runtime through `/api/models/download`, or copied in by hand; each holds `encoder.int8.onnx`, `decoder.int8.onnx`, `tokens.txt` |

## Build & run

```bash
# 1. put the three files above under lib/ (create it)

# 2. compile (the service runs straight from the .class files)
javac -cp lib/sherpa-onnx-jvm.jar SttServer.java

# 3. run (manual / dev)
java -cp .:lib/sherpa-onnx-jvm.jar -Djava.library.path=lib \
     [-Dstt.bind=127.0.0.1] [-Dstt.port=8092] [-Dstt.threads=8] \
     [-Dstt.models.dir=./models] SttServer

# 4. install a model (no restart needed — the service re-scans on install)
curl -X POST 'http://127.0.0.1:8092/api/models/download?model=small&source=github'
```

Confirm it is up before installing anything:

```bash
curl -s localhost:8092/api/audio/health   # status "no-models" (or "ready" once a model is in)
curl -s localhost:8092/api/models         # catalogue, installed models, free disk
```

There is **no model required to start**: with an empty models directory the service starts
in model-less mode, reports `status:"no-models"` on `/api/audio/health`, and answers `503`
on `/api/audio/transcribe` until a model is installed. Typing, the app and the browser
recogniser keep working.

In production here it runs under a systemd unit `engtop-stt`:

```bash
sudo systemctl start|status|restart engtop-stt
journalctl -u engtop-stt -f
```

System properties: `stt.bind` (default `127.0.0.1`; the unit uses `0.0.0.0` so the webtop
container can reach it — firewalld does **not** expose the port to the LAN), `stt.port`
(8092), `stt.threads` (8; the unit uses 16), `stt.models.dir`
(`/home/opc/java-stt/models`), `stt.default-model` (`medium`), `stt.vad` (off),
`stt.vad.model` (`/home/opc/java-stt/silero_vad.onnx`).

## Endpoints

* `GET /api/audio/health` →
  `{"ok":true,"status":"ready","model":"medium","models":["small","medium","large-v3"],
    "default_model":"medium","loaded":["medium:en",…],"engine":"sherpa-onnx (Java)",
    "languages":["en","ta"],"modelsDir":"…","requests":…,"uptime_seconds":…}`

  When no model is installed the service still starts (it no longer throws): `status` is
  `"no-models"`, `models` is empty, and the engine string says so. Typing, the browser
  recognizer and the chat tools are unaffected — only server transcription is unavailable,
  and `/api/audio/transcribe` answers `503` with a clear message until a model is installed.
* `POST /api/audio/transcribe[?language=auto|en|ta][?model=small|medium|large-v3]` —
  body: raw WAV bytes (`Content-Type: audio/wav`), or multipart with fields `audio` +
  optional `language` + optional `model` (`X-Language` / `X-Model` headers also work);
  `X-Filename` accepted, 25 MB cap →
  `{"ok":true,"text","language","model","language_probability","duration","sample_rate",
    "elapsed_ms","inference_ms"}`

Auto detection is two-pass: English first, and if the result is empty or a bracketed/starred
meta line, a second decode as Tamil. Clips below RMS 0.005 return `text:""` without decoding.
The WAV parser accepts PCM 8/16/24/32-bit int plus float32, any sample rate, mono or
downmixed.

## Model management API

Models can be installed and removed over HTTP, so a fresh deployment needs no manual copying.
All four endpoints need no API key. The voice page's 📦 panel drives them.

* `GET /api/models` → installed models (with sizes), the catalogue, free disk, and the sources.
* `GET /api/models/progress` → `phase` (`idle`/`downloading`/`extracting`/`done`/`error`),
  `received`, `total`, `percent`, `error`. Poll this while a download runs.
* `POST /api/models/download?model=small|medium|large-v3&source=github|huggingface` →
  `202` immediately; the download runs on a background thread. `409` if one is already
  running, `507` if there is not enough free disk.
* `POST /api/models/delete?model=<name>` → removes the directory and frees the disk;
  refuses any path outside the models root.

**Sources.** `github` fetches the single `sherpa-onnx-whisper-<size>.tar.bz2` release asset
and extracts it (HTTP Range resume, so an interrupted download continues). `huggingface`
fetches the three loose int8 files from `csukuangfj/sherpa-onnx-whisper-<size>` and renames
them. Either way only the three files the recognizer reads are kept — the full-precision
copies inside the tar are discarded, so `small` lands at ~359 MB, not ~1.3 GB.

> Model-less start, the management API and the UI panel were added so the app runs anywhere:
> a jar with no models still serves chat, typing and the browser recognizer, and a model can
> be installed from the page.

## Choosing a model

Model dirs: `models/<name>/{encoder.int8.onnx,decoder.int8.onnx,tokens.txt}` — discovered
at startup and re-scanned after each install/delete (small, medium, large-v3 today). The
model is chosen per request (the voice page ships a dropdown; unknown names get a 400
listing the options). Recognizers are built lazily per (model, language), cached in RAM, and
pre-warmed at startup — the default model first, the rest by a background thread — so
requests after warm-up are a pure decode. A newly downloaded model is warmed the same way.

Measured on this host (16 threads, auto mode):

| Model | English 6.6 s clip | Tamil 4.8 s clip (two passes) | Quality |
| --- | --- | --- | --- |
| `small` | ~1.4 s | ~2.4 s | decent English, weak on accents/Tamil |
| `medium` (default) | ~4.7 s | ~6.8 s | clearly better English |
| `large-v3` | ~8.6 s | ~18.9 s | exact on the test clip; strongest Tamil |

## Preprocessing (accuracy work, Apr 2026)

* **Server-side resampling.** Every clip is converted to 16 kHz with a windowed-sinc kernel
  (`resample()`) before decoding. sherpa-onnx resamples internally, but its cheaper kernel
  is noticeably worse on non-integer ratios such as the 22050 Hz clips browsers/phones
  produce. We now own the conversion.
* **Silero VAD (optional, off by default).** `-Dstt.vad=true` splits the clip on pauses
  (Silero, threshold 0.5, 0.35 s min silence) and decodes each speech segment. Useful for
  long, pause-heavy dictation; leave it **off** for short clips — hard gating can clip soft
  Tamil plosives. Model: `silero_vad.onnx` next to this README.
* **Mic capture (browser).** `noiseSuppression` and `autoGainControl` are requested **off**
  (plain booleans, with a `{audio:true}` fallback so Chrome cannot silently pick another
  device): the phone-call DSP smears quiet/accented speech. Echo cancellation stays on.
* **Recorder graph (browser).** The mic is one `MediaStreamSource` feeding the meter
  analyser; the recorder tap hangs off the same node and reaches the destination through a
  gain of `0.0001`. A fully muted (`gain=0`) node stops Chrome from pumping
  `onaudioprocess`, so the old graph captured pure silence — the server then correctly
  answered `text:""` and the page said "no speech in that clip" no matter what was said.
  A client-side peak check now reports `your microphone produced no signal` instead of
  blaming the server.

### What we ruled out

* **Piper** — text-to-speech, wrong direction, and has no Tamil voice (`rhasspy/piper-voices`
  has no `ta`).
* **NVIDIA Parakeet / Canary (NeMo)** — Parakeet TDT 0.6b is fast and strong on *English*,
  but this EPYC 7J13 has **no AVX-512**, which its fast int8 path relies on, and it is
  English-only — no Tamil. Canary has Tamil but is 1B+, autoregressive, and would be slow
  without AVX-512.
* **Distil-Whisper large-v3.5** — available (`sherpa-onnx-whisper-distil-large-v3.5`), but
  it is English-only distillation; Whisper `large-v3` already covers Tamil better.

If English accuracy is still the problem after the mic/DSP fix, the next lever is a cloud
STT API (Google/Azure) — the only route to genuinely Google-class accuracy, at the cost of
sending audio off-box.

The voice page exposes this without any key: **the browser's own `SpeechRecognition`**
("Browser (Google, free)" in the model dropdown). Chrome/Chromium only, audio goes to
Google, English + Tamil, live interim results, zero cost — and it falls back to the local
models when unavailable. See `../engtop/stt/README.md`.

## Test results (last run, this host)

| Case | Result |
| --- | --- |
| auto, 3.1 s English clip | `Hello, how are you today? The rhythm looks great.` — ~0.75 s |
| auto, 4.8 s Tamil clip | correct Tamil, ~2.1 s (two passes) |
| silence clip | `text:""` immediately, no hallucination |
| forced `en` / `ta` | correct (English forced to `ta` → Tamil transliteration) |
| multipart + raw body + bad language + empty body + non-WAV body | all correct (200 / 400) |
| WAV produced by the page's own `encodeWav()` JS | parses (`sample_rate:16000`, duration exact) |
| container → host (`169.254.1.2:8092`) and host loopback | both healthy |
| auto, 6.6 s English clip × small/medium/large-v3 | all correct; large-v3 exact — 1.4 / 4.7 / 8.6 s |
| auto, 4.8 s Tamil clip × small/medium/large-v3 | Tamil script on all three (needed the `*meta*` fallback fix) |
| `model=nope` | 400 `unknown model 'nope' — available: [small, medium, large-v3]` |
| public URL, model field through both nginx layers | 200 per model; page ships `#modelSel` dropdown |
