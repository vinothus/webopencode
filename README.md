# OpenCode Spring Boot

One project, one jar, one process.

A Java 17+ / Spring Boot 3.5 application that **sends requests to, and receives responses
from, AI models through the OpenCode gateway**, exposes them as an **OpenAPI-documented REST
API**, and ships a **local speech-to-text engine** — Whisper via the sherpa-onnx JNI bindings —
that runs inside the same JVM.

The wire protocol is the OpenAI chat-completions format, spoken the way the **OpenCode CLI**
speaks it. Everything here is a port of the reference implementation in the PawWork Android
client (`com.pawwork.android.chat.ChatFragment` + `ToolRegistry`), including the gateway quirks
that client had to learn the hard way.

| Piece | Where |
|---|---|
| Gateway client, SSE streaming, tool loop | `src/main/java/com/pawwork/opencode/{client,service,tools,model,config}` |
| Web app, REST API, Swagger UI, voice page | `src/main/java/com/pawwork/opencode/demo`, `src/main/resources/static/voice-chat.html` |
| Local speech-to-text engine | `src/main/java/com/pawwork/opencode/stt` |

There is no second project, no second folder and no sidecar to deploy. Speech-to-text is a
library inside this jar, wired into the application by auto-configuration.

---

## Quick start

Requirements: **JDK 17+** (tested on JDK 21). The Maven wrapper downloads Maven 3.9.11 and
all dependencies, so nothing else needs installing.

### 1. Install the sherpa-onnx bindings

The sherpa-onnx JVM bindings are **not published to Maven Central or JitPack**, so the build
cannot resolve them on its own. Download the jar once and install it into your local Maven
repository — then every later build is an ordinary `./mvnw package`:

```bash
mkdir -p lib
curl -L -o lib/sherpa-onnx-jvm.jar \
  https://github.com/csukuangfj/sherpa-onnx/releases/download/v1.12.1/sherpa-onnx-v1.12.1-java21.jar

./scripts/install-stt-jar.sh      # -> com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.1 in ~/.m2
```

The **native** libraries are needed at runtime, not at build time. Put them in `lib/` too:

```bash
curl -L https://github.com/csukuangfj/sherpa-onnx/releases/download/v1.12.1/sherpa-onnx-v1.12.1-linux-x64-jni.tar.bz2 \
  | tar -xj -C /tmp
cp /tmp/sherpa-onnx-*-linux-x64-jni/lib/*.so lib/
```

> Pick the archive for your platform. Linux x86-64 is shown above; the release page also has
> aarch64, macOS and Windows builds.

### Running on Windows

Everything above works from **Command Prompt** (`cmd`) or **PowerShell**. `setup.bat` does the
whole one-time setup — the bindings, the native libraries, the Maven install and the build:

```bat
cd C:\projects\webopencode
setup.bat
```

Then start it:

```bat
java -Djava.library.path=lib -jar target\opencode-spring-boot-1.0.0.jar
```

By hand, the same three steps are:

```bat
:: 1. the sherpa-onnx JVM bindings -> lib\ and then into the local Maven repository
curl -L -o lib\sherpa-onnx-jvm.jar ^
  https://github.com/csukuangfj/sherpa-onnx/releases/download/v1.12.1/sherpa-onnx-v1.12.1-java21.jar

mvnw.cmd install:install-file ^
  -Dfile=lib\sherpa-onnx-jvm.jar ^
  -DgroupId=com.k2fsa.sherpa.onnx -DartifactId=sherpa-onnx -Dversion=1.12.1 -Dpackaging=jar

:: 2. the native libraries -> lib\  (Windows 10/11 ship a bsdtar that reads .tar.bz2)
curl -L -o win-jni.tar.bz2 ^
  https://github.com/csukuangfj/sherpa-onnx/releases/download/v1.12.1/sherpa-onnx-v1.12.1-win-x64-jni.tar.bz2
tar -xjf win-jni.tar.bz2
copy sherpa-onnx-v1.12.1-win-x64-jni\lib\*.dll lib\

:: 3. build
mvnw.cmd clean package
```

`lib\` then holds `sherpa-onnx-jni.dll`, `onnxruntime.dll`, `onnxruntime_providers_shared.dll`
and `sherpa-onnx-cxx-api.dll`. Keep all four together — the JNI library resolves them from
its own directory.

The standalone transcriber works the same way:

```bat
java -Djava.library.path=lib -Dloader.main=com.pawwork.opencode.stt.SttServer ^
  -Dstt.models.dir=models -Dstt.bind=0.0.0.0 -Dstt.port=8092 ^
  -cp target\opencode-spring-boot-1.0.0.jar ^
  org.springframework.boot.loader.launch.PropertiesLauncher
```

### 2. Build and run

```bash
./mvnw package                    # one module, one jar
java -Djava.library.path=lib -jar target/opencode-spring-boot-1.0.0.jar
```

Or during development:

```bash
./mvnw spring-boot:run           # http://localhost:8080
```

Other port / no native libraries:

```bash
java -Djava.library.path=lib -jar target/opencode-spring-boot-1.0.0.jar --server.port=9090
```

**No API key is needed**: the default model (`big-pickle`) runs on the free `opencode.ai/zen`
gateway. Export `OPENAI_API_KEY` / `DEEPSEEK_API_KEY` etc. only if you want a paid provider,
then select that model in the page.

* Voice page → <http://localhost:8080/voice-chat.html> — speak, and the **server** turns it into
  text (English + தமிழ்) before the model sees it.
* Swagger UI → <http://localhost:8080/swagger-ui.html>
* OpenAPI JSON → <http://localhost:8080/v3/api-docs>

Ask something that forces a tool call:

```bash
curl -s localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"How many seconds are in a week? Use the calculate tool."}' | python3 -m json.tool
```

```json
{
  "sessionId": "sess_1a2b3c4d5e6f7a8b9c0d",
  "provider": "zen",
  "model": "big-pickle",
  "reply": "A week has 604,800 seconds.",
  "rounds": 2,
  "toolExecutions": [ { "name": "calculate", "arguments": "{\"expression\":\"7*24*60*60\"}",
                        "result": "{\"expression\":\"7*24*60*60\",\"result\":604800.0}",
                        "success": true, "durationMs": 1 } ],
  "usage": { "promptTokens": 812, "completionTokens": 96, "totalTokens": 908 },
  "historySize": 4
}
```

Keep the conversation going by sending `sessionId` back:

```bash
curl -s localhost:8080/api/chat -H 'Content-Type: application/json' \
     -d '{"sessionId":"sess_1a2b3c4d5e6f7a8b9c0d","message":"And a month of 30 days?"}'
```

---

## Speech-to-text

**Optional.** The app boots and serves the page without a model — typing, the coding tools and
the browser recogniser all work. Only server-side transcription needs a Whisper model, and you
can install one from the page's 📦 panel at runtime.

The engine runs **in this JVM**: the browser captures the audio, POSTs it to
`/api/audio/transcribe`, and the decoding, the language detection and the Whisper inference all
happen here, on this machine. No cloud STT is involved. A browser speech engine can be selected
instead (it is free and runs in Chrome/Edge) — that one does send audio to Google.

```bash
curl -s localhost:8080/api/audio/health
# {"ok":true,"status":"no-models","model":"","models":[],…}
```

`status` is `no-models` until a model is installed, `ready` afterwards, and `error` if the
native libraries could not be loaded (the message says how to fix it).

### Installing a model

No model is needed to start, and none needs to be copied by hand:

```bash
curl -X POST 'localhost:8080/api/models/download?model=small&source=github'
curl -s localhost:8080/api/models/progress     # phase / received / total / percent
```

The model is picked up without a restart. `source` is `github` (one release tarball, HTTP Range
resume) or `huggingface` (three loose int8 files). Either way only the three files the
recogniser reads are kept, so `small` lands at ~359 MB rather than ~1.3 GB.

### Configuration

Everything lives under `stt:` in `src/main/resources/application.yml`:

| Key | Default | What it does |
|---|---|---|
| `stt.mode` | `embedded` | `embedded` runs the engine in this JVM; `remote` talks to a separate speech-to-text service over HTTP. |
| `stt.models-dir` | `models` | Where the Whisper models live, one sub-directory per size. |
| `stt.threads` | `8` | Threads each recogniser may use. |
| `stt.default-model` | `medium` | The model used when a request does not name one. |
| `stt.vad` | `false` | Silero voice-activity detection — splits long clips on pauses. |
| `stt.vad-model` | *(blank)* | Path to the Silero VAD model. Blank uses the copy bundled in the jar. |
| `stt.timeout-seconds` | `180` | How long a transcription may take. |
| `stt.max-bytes` | `26214400` | Largest accepted audio payload (25 MB). |
| `stt.base-url` | `http://127.0.0.1:8092/api/audio` | Only used when `stt.mode=remote`. |

### Running the transcriber as a separate process

The same jar can also be started as a **dedicated** speech-to-text service — the arrangement for
a containerised web app reaching a transcriber on the host. Point the app at it with
`stt.mode=remote` and `stt.base-url`:

```bash
java -Djava.library.path=lib \
     -Dloader.main=com.pawwork.opencode.stt.SttServer \
     -Dstt.models.dir=./models -Dstt.bind=0.0.0.0 -Dstt.port=8092 \
     -cp target/opencode-spring-boot-1.0.0.jar \
     org.springframework.boot.loader.launch.PropertiesLauncher
```

(`-Dloader.main=` picks the entry point inside the fat jar; the plain `java -jar` form always
starts the web application.)

System properties: `stt.bind` (default `127.0.0.1`), `stt.port` (8092), `stt.threads` (8),
`stt.models.dir` (`./models`), `stt.default-model` (`medium`), `stt.vad` (off),
`stt.vad.model` (the bundled Silero model).

---

## REST surface

### `/api` — the application API

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/chat` | One turn: model ⇄ tools ⇄ model until a final answer (blocking). |
| `POST` | `/api/chat/stream` | Same turn as **SSE** — `start`, `round`, `token`, `reasoning`, `tool_start`, `tool_result`, `done`, `error`. |
| `GET` | `/api/chat/{sessionId}/history` | The full wire history, tool messages included. |
| `DELETE` | `/api/chat/{sessionId}` | Forget a session. |
| `GET` | `/api/sessions` | How many conversations are in memory. |
| `GET` | `/api/providers` | Configured providers. |
| `GET` | `/api/tools` | Tool names advertised to the model. |
| `POST` | `/api/audio/transcribe` | **Speech → text**: multipart `audio` + `language=auto\|en\|ta` (+ optional `model`) in, transcript JSON out. |
| `POST` | `/api/audio/transcribe/raw` | Same engine, raw audio body + `X-Language` / `X-Model` headers. |
| `GET` | `/api/audio/health` | Speech-to-text engine status (model, load state, languages). |
| `GET` | `/api/models` | Installed models, the catalogue, free disk, the sources. |
| `GET` | `/api/models/progress` | Progress of the in-flight model download. |
| `POST` | `/api/models/download` | Install a Whisper model (`model`, `source`). |
| `POST` | `/api/models/delete` | Remove an installed model and free its disk. |

The `/api/models*` paths are what the voice page's 📦 panel calls. The same operations are
also under `/api/audio/models*` for deployments that route the paths differently.

### `/v1` — OpenAI-compatible

| Method | Path | What it does |
|---|---|---|
| `POST` | `/v1/chat/completions` | OpenAI-shaped chat completions, streaming and non-streaming. |
| `POST` | `/v1/completions` | Legacy completions. |
| `POST` | `/v1/embeddings` | Embeddings. |
| `GET` | `/v1/models` | The models this deployment advertises. |
| `GET` | `/v1/health` | Library health. |

### Speech-to-text details

```bash
curl -F audio=@clip.wav -F language=ta localhost:8080/api/audio/transcribe
# {"ok":true,"text":"வணக்கம் நீங்கள் எப்படி இருக்கிறீர்கள்","language":"ta",
#  "model":"medium","language_probability":1.0,"duration":4.8,"sample_rate":16000,
#  "elapsed_ms":1844,"inference_ms":1702}
```

Auto detection is two-pass: English first, and if the result is empty or a bracketed/starred
meta line, a second decode as Tamil. Clips below RMS 0.005 return `text:""` without decoding —
whisper hallucinates on silence. The WAV parser accepts PCM 8/16/24/32-bit int plus float32,
any sample rate, mono or downmixed.

### Model management API

Models can be installed and removed over HTTP, so a fresh deployment needs no manual copying.
None of the four endpoints need an API key.

* `GET /api/models` → installed models (with sizes), the catalogue, free disk, and the sources.
* `GET /api/models/progress` → `phase` (`idle`/`downloading`/`extracting`/`done`/`error`),
  `received`, `total`, `percent`, `error`. Poll this while a download runs.
* `POST /api/models/download?model=small|medium|large-v3&source=github|huggingface` →
  `202` immediately; the download runs on a background thread. `409` if one is already
  running, `507` if there is not enough free disk.
* `POST /api/models/delete?model=<name>` → removes the directory and frees the disk;
  refuses any path outside the models root.

### Choosing a model

Model dirs are `models/<name>/{encoder.int8.onnx,decoder.int8.onnx,tokens.txt}` — discovered
at startup and re-scanned after each install/delete. The model is chosen per request (the voice
page ships a dropdown; unknown names get a 400 listing the options). Recognisers are built
lazily per (model, language), cached in RAM, and warmed in the background at startup, so the
first request after a boot does not pay the load cost.

| Model | Approx. size | Good for |
|---|---|---|
| `small` | 359 MB | Fast; decent English, weak on accents and Tamil. |
| `medium` (default) | 903 MB | Clearly better English. |
| `large-v3` | 1.7 GB | Best quality, strongest Tamil, slowest. |

### Preprocessing (accuracy work, Apr 2026)

* **Server-side resampling.** Every clip is converted to 16 kHz with a windowed-sinc kernel
  (`SttEngine.resample()`) before decoding. sherpa-onnx resamples internally, but its cheaper
  kernel is noticeably worse on non-integer ratios such as the 22050 Hz clips browsers/phones
  produce. We now own the conversion.
* **Silero VAD (optional, off by default).** `stt.vad=true` splits the clip on pauses
  (Silero, threshold 0.5, 0.35 s min silence) and decodes each speech segment. Useful for
  long, pause-heavy dictation; leave it **off** for short clips — hard gating can clip soft
  Tamil plosives.
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
models when unavailable.

---

## Tools

Three local tools are always advertised, plus the eleven official CLI tools:

| Tool | What it does |
|---|---|
| `current_time` | The current date and time. |
| `calculate` | Evaluates a math expression. |
| `http_fetch` | Fetches a URL. |
| `read`, `glob`, `grep`, `write`, `edit`, `bash`, `todowrite`, `webfetch`, `skill`, `task`, `websearch` | The official OpenCode CLI tool set. |

The declarations live in `src/main/resources/opencode_tools.json` and are prepended to the
tools the gateway advertises.

---

## Layout

```
.
├── pom.xml                       one module, one jar
├── mvnw, mvnw.cmd, .mvn/         the Maven wrapper
├── setup.bat                     one-time setup on Windows
├── scripts/install-stt-jar.sh    installs the sherpa-onnx bindings into ~/.m2
├── lib/                          sherpa jar + native libraries   (gitignored)
├── models/                       Whisper models                  (gitignored)
└── src
    ├── main/java/com/pawwork/opencode
    │   ├── client/ config/ model/ tools/ service/ autoconfigure/   gateway client + tool loop
    │   ├── demo/                                                    web app, REST API
    │   └── stt/                                                     speech-to-text engine
    ├── main/resources
    │   ├── application.yml, opencode_tools.json, silero_vad.onnx
    │   ├── static/voice-chat.html
    │   └── META-INF/spring/…AutoConfiguration.imports
    └── test/java
```

## Build and test

```bash
./mvnw test                 # the unit tests; no network
./mvnw -Dopencode.test.live=true test    # adds the live gateway tests
./mvnw package              # target/opencode-spring-boot-1.0.0.jar
```

## Notes

* `opencode.tools.skills-dir` defaults to `/config/Engineering/pawwork-linux/skills`. Start
  with `--opencode.tools.skills-dir=` on a machine that does not have it.
* The native libraries cannot be redistributed inside the jar, so they stay in `lib/` and are
  found through `-Djava.library.path=lib`. `spring-boot:run` sets that automatically;
  `java -jar` needs the flag.
* See [`SAMPLES.md`](SAMPLES.md) for worked request/response examples.
