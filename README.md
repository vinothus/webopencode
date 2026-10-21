# webopencode

A server-side voice + text chat application built on Spring Boot, with local
speech-to-text (English + தமிழ்) and an AI that can execute coding tools.

Everything runs on your own machine: no audio, no transcript and no code leaves
the host except the model calls you configure.

## Repository layout

| Path | What it is |
| --- | --- |
| [`opencode-spring-boot/`](opencode-spring-boot/) | The application. A Maven multi-module Spring Boot project: `opencode-spring-boot-starter` (the reusable chat/tool engine) and `opencode-demo-app` (the web app, REST API and `voice-chat.html`). |
| [`stt/`](stt/) | The local speech-to-text service — a single-file Java program (`SttServer.java`) using sherpa-onnx Whisper. It answers `/api/audio/*` and manages its own models over `/api/models/*`. |

## What it does

- **Voice chat page** (`/voice-chat.html`) — records in the browser, transcribes
  on the server, then sends the text through a normal chat turn.
- **Two speech engines** — a local Whisper model (server-side, accurate, works in
  any browser) or the browser's built-in recogniser (Chrome/Edge, free).
  Three Whisper sizes are selectable per request: `small`, `medium`, `large-v3`.
- **Coding tools** the AI can actually run: `read`, `glob`, `grep`, `write`,
  `edit`, `bash`, `todowrite`, `webfetch`, `skill`, `task` — implemented in Java,
  scoped to a workspace, with `bash` always requiring approval.
- **Tool modes** — `ask` (pause and confirm), `auto` (run file tools freely) or
  `off`.
- **Model management in the UI** — a fresh install ships with no models and still
  serves chat, typing and the browser recogniser; a model can be downloaded from
  the page (GitHub releases or Hugging Face) and appears in the engine dropdown.

## Getting started

### Requirements

- **JDK 17 or newer** for the application (tested on JDK 21). Check with `java -version`.
- **JDK 21 or newer** for the STT service.
- No database, no message broker, no build tools to install — the Maven wrapper
  (`./mvnw`) downloads Maven and all dependencies on first run.

### Quick start (no speech-to-text)

You can run the app on its own first. It starts fine without the STT service:
typing, the coding tools and the browser recogniser all work, and any
transcription request that needs the server will report the service is missing
instead of failing.

```bash
# 1. clone
git clone https://github.com/vinothus/webopencode.git
cd webopencode/opencode-spring-boot

# 2. build (first run downloads Maven + dependencies, a few minutes)
./mvnw -DskipTests package

# 3. run
java -jar opencode-demo-app/target/opencode-demo-app-1.0.0.jar
```

Then open **<http://localhost:8080/voice-chat.html>**. The app is ready when that
page returns — startup takes a few seconds.

To confirm the two halves are alive:

```bash
curl -s localhost:8080/voice-chat.html  -o /dev/null -w 'page   : %{http_code}\n'   # 200
curl -s localhost:8080/api/tools                                          # JSON: the tools
curl -s localhost:8080/api/audio/health                                   # STT status
```

No API key is needed to chat: the default model (`big-pickle`) is served by the
free [`opencode.ai/zen`](https://opencode.ai) gateway. To use a paid provider
instead, export a key before starting — for example
`export OPENAI_API_KEY=sk-...` or `export DEEPSEEK_API_KEY=...` — and pick that
model in the page.

To run on a different port or keep it running in the background:

```bash
# different port
java -jar opencode-demo-app/target/opencode-demo-app-1.0.0.jar --server.port=9090

# background, logs to app.log
nohup java -jar opencode-demo-app/target/opencode-demo-app-1.0.0.jar > app.log 2>&1 &
```

### Full setup (with local speech-to-text)

Do the quick start, then bring up the STT service and point the app at it:

```bash
# 1. build and run the STT service (see stt/README.md for the lib/ downloads)
cd ../stt
javac -cp lib/sherpa-onnx-jvm.jar SttServer.java
java -cp .:lib/sherpa-onnx-jvm.jar -Djava.library.path=lib \
     -Dstt.bind=127.0.0.1 -Dstt.port=8092 -Dstt.models.dir=./models SttServer

# 2. install a model from the UI: open the page, click the 📦 button, download
#    one (small / medium / large-v3), then pick it in the engine dropdown.
#    Or from a terminal:
curl -X POST 'http://127.0.0.1:8092/api/models/download?model=small&source=github'
```

Two settings connect the two services — both are meant to be overridden per
deployment:

| Setting | Purpose |
| --- | --- |
| `stt.base-url` | Where the app finds the STT service (default `http://host.containers.internal:8092/api/audio`). On a plain host, run the app with `--stt.base-url=http://127.0.0.1:8092/api/audio`. |
| `opencode.tools.workspace` | The directory the file/shell tools are allowed to touch. Blank means "the process working directory". |

Other settings live in
`opencode-spring-boot/opencode-demo-app/src/main/resources/application.yml`.
API keys are read from the environment (`OPENAI_API_KEY`, `DEEPSEEK_API_KEY`, …)
and default to blank, so nothing secret is stored in the repository. Any property
can be overridden on the command line with `--property.name=value` or through
`SPRING_APPLICATION_JSON`.

### 2. The speech-to-text service

Full instructions are in [`stt/README.md`](stt/README.md). In short: obtain the
three files under `stt/lib/`, compile `SttServer.java`, run it (no model required
to start), then install one — either from the 📦 panel on the page or with:

```bash
curl -X POST 'http://127.0.0.1:8092/api/models/download?model=small&source=github'
```

## Documentation

- [`opencode-spring-boot/README.md`](opencode-spring-boot/README.md) — the API,
  the tools, the chat pipeline and the front end.
- [`opencode-spring-boot/SAMPLES.md`](opencode-spring-boot/SAMPLES.md) — request
  and response examples.
- [`stt/README.md`](stt/README.md) — the speech-to-text service, its models,
  accuracy work and measured timings.

## Requirements

- **Java 17+** for the application (built against Spring Boot 3.5).
- **Java 21+** for the STT service.
- A local Whisper model if you want server-side transcription — optional, and
  installable from the UI.
