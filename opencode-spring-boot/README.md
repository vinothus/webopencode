# OpenCode Spring Boot Library

A Java 17 / Spring Boot 3.5 library that **sends requests to, and receives responses from, AI
models through the OpenCode gateway**, exposed as an **OpenAPI-documented REST API**.

The wire protocol is the OpenAI chat-completions format, spoken the way the **OpenCode CLI** speaks
it. Everything here is a port of the reference implementation in the PawWork Android client
(`com.pawwork.android.chat.ChatFragment` + `ToolRegistry`), including the gateway quirks that
client had to learn the hard way.

| Module | What it is |
|---|---|
| `opencode-spring-boot-starter` | The reusable library. Auto-configures when it lands on a Spring Boot classpath. |
| `opencode-demo-app` | A runnable app that exposes the library over REST + Swagger UI. |

---

## Quick start

```bash
cd opencode-spring-boot
./mvnw install                   # builds both modules (wrapper fetches Maven 3.9.11)
cd opencode-demo-app
../mvnw spring-boot:run          # http://localhost:8080
```

* Swagger UI → <http://localhost:8080/swagger-ui.html>
* OpenAPI JSON → <http://localhost:8080/v3/api-docs>
* Voice page → <http://localhost:8080/voice-chat.html> — speak, and the **server** turns it into
  text (English + தமிழ்) before the model sees it. Start the local transcriber first:
  `../stt/start-stt.sh`

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

## REST surface

### `/api` — the library's own API

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/chat` | One turn: model ⇄ tools ⇄ model until a final answer (blocking). |
| `POST` | `/api/chat/stream` | Same turn as **SSE** — `start`, `round`, `token`, `reasoning`, `tool_start`, `tool_result`, `done`, `error`. |
| `GET` | `/api/chat/{sessionId}/history` | The full wire history, tool messages included. |
| `DELETE` | `/api/chat/{sessionId}` | Forget a session. |
| `GET` | `/api/sessions` | How many conversations are in memory. |
| `GET` | `/api/providers` | Configured providers. |
| `GET` | `/api/tools` | Tool names advertised to the model. |
| `POST` | `/api/audio/transcribe` | **Speech → text**: multipart `audio` + `language=auto\|en\|ta` in, transcript JSON out (English + Tamil, local Whisper). |
| `POST` | `/api/audio/transcribe/raw` | Same engine, raw audio body + `X-Language` / `Content-Type` headers. |
| `GET` | `/api/audio/health` | Speech-to-text sidecar status (model, load state, languages). |

### `/api/audio` — server-side speech-to-text (English + தமிழ்)

The voice page (`/voice-chat.html`) only **captures** audio — WebRTC `getUserMedia` +
`MediaRecorder` — and uploads the clip. `AudioController` hands the bytes to the local Java
**sherpa-onnx Whisper** service (systemd `engtop-stt`), which decodes them, detects the
language and transcribes **on this machine**; the text then goes through the normal
`/api/chat/stream` turn. No cloud STT is involved. A browser speech engine can be selected
instead (it is free and runs in Chrome/Edge).

```bash
curl -F audio=@clip.mp3 -F language=ta http://localhost:8080/api/audio/transcribe
# {"ok":true,"text":"வணக்கம் நீங்கள் எப்படி இருக்கிறீர்கள்","language":"ta",
#  "language_probability":0.987,"elapsed_ms":1844,"segments":[…]}
```

`language=auto` lets the model pick between English and Tamil (uncertain clips are re-decoded
per language and the more confident hypothesis wins). The service is the Java `SttServer`
described in `../stt/README.md`.

### `/api/models` — install and remove speech models

The page's 📦 panel installs models over HTTP, so a deployment with no models still works:
chat, typing and the browser recogniser are unaffected, and server transcription simply says
"no model installed" until one is added.

```bash
curl http://localhost:8080/api/audio/models              # installed + catalogue + free disk
curl -X POST 'http://localhost:8080/api/audio/models/download?model=small&source=github'
curl http://localhost:8080/api/audio/models/progress     # phase, bytes, percent
curl -X POST 'http://localhost:8080/api/audio/models/delete?model=small'
```

`AudioController` proxies these to the STT service's `/api/models`; nginx also routes
`/api/models` straight there (see the host vhost). Two sources are offered — GitHub release
tarballs with HTTP Range resume, or Hugging Face loose files. Downloads run in the background
and are polled; a half-finished download resumes rather than restarting.

### `/v1` — the OpenAI-spec API (Cursor / OpenAI SDK / LangChain)

| Method | Path | What it does |
|---|---|---|
| `POST` | `/v1/chat/completions` | Standard OpenAI body in, standard OpenAI answer out. `stream=true` relays `chat.completion.chunk` SSE frames terminated by `data: [DONE]`. Every spec field — `temperature`, `top_p`, `n`, `stop`, `seed`, `max_tokens`, `max_completion_tokens`, `response_format`, `logit_bias`, `logprobs`, `parallel_tool_calls`, `metadata`, … — is forwarded, never dropped. |
| `GET` | `/v1/models` | The provider's catalogue in the OpenAI list shape: `{"object":"list","data":[…]}`. |
| `GET` | `/v1/models/{model}` | `models.retrieve`: the model object, or `404 invalid_request_error`. |
| `GET` | `/v1/health` | Library status + default provider. |

What makes it spec-compatible:

* answers are `chat.completion` / `chat.completion.chunk` objects with `chatcmpl-…` ids and
  `usage` when the provider reports it;
* errors use OpenAI's object — `{"error":{"message":…,"type":…,"param":…,"code":…}}` — with
  `type` mapped from the status (`invalid_request_error`, `authentication_error`,
  `permission_error`, `rate_limit_error`, `api_error`, `server_error`), plus the provider's raw
  body in a non-standard `upstream` field;
* `Authorization: Bearer …` is accepted and ignored (the upstream credential comes from
  `opencode.providers.*.api-key`), and the OpenAPI document declares a `BearerAuth` scheme;
* **declared tools stay declared.** The free tier refuses any gateway body without the 11 official
  CLI tool names, so the starter injects them — but a call to a tool the client never sent is
  answered in-band and the model is asked again (up to 3 rounds), so `tool_calls` only ever names
  what you declared. A request with no `tools` at all gets `tool_choice:"none"` and comes back as
  plain content;
* streamed responses relay role/reasoning/content frames live and hold back only tool-call frames
  until they can be verified, so a client never sees an undeclared tool name;
* the OpenAPI document names its schemas after the official spec (`ChatCompletionRequest`,
  `ChatCompletion`, `ChatCompletionChunk`, `ChatCompletionMessage`, `ChatCompletionTool`,
  `CompletionUsage`, `ErrorObject`, …).

Point any OpenAI SDK at it:

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8080/v1", api_key="not-needed")
print(client.models.list().data[0].id)
print(client.chat.completions.create(
    model="big-pickle",
    messages=[{"role": "user", "content": "Say hi"}],
).choices[0].message.content)
```

Select a provider with the `X-Provider` header (or `provider` on `/api/chat`).

---

## What the starter does for you

**1. OpenCode gateway dialect.** For any provider flagged as a gateway (anything on `opencode.ai`,
or `opencode-gateway: true`) every request carries:

```
x-opencode-session: ses_<26 hex>        # required; hashed from stable-session-id
x-opencode-client:  cli
x-opencode-project: global
x-opencode-request: msg_<12 hex><14 alnum>
User-Agent:         opencode/1.18.31
```

The session id is `SHA-256(stable-session-id)` truncated into the shape the gateway accepts, so the
same seed always maps to the same id — same behaviour as the Android client's
`openCodeSessionId()`.

**2. Streaming is the transport, JSON is the interface.** The Zen free tier answers `403` to
non-streaming calls, so the client always sends `stream:true` (+ `stream_options.include_usage`)
and folds the SSE stream back into a single `ChatResponse`. Callers who *do* want the stream get
the frames relayed verbatim.

**3. The official CLI tool schemas.** The free tier answers `403 FreeTierError` unless the request
advertises the 11 official tool names (`bash`, `edit`, `glob`, `grep`, `read`, `skill`, `task`,
`todowrite`, `webfetch`, `websearch`, `write`). They ship in
`opencode-spring-boot-starter/src/main/resources/opencode_tools.json` and are prepended
automatically — a tool you register yourself under one of those names keeps precedence.

**4. The tool loop.** Up to `opencode.max-tool-rounds` (default 12) model→tool→model rounds per
turn, with loop-busting: N consecutive failures of the *identical* call stop the turn and say so.

**5. Retries.** 3 attempts on I/O failure (1s backoff, 1.5s for DNS flakes), plus `429/5xx`
gateway answers. A stream that already emitted chunks is never replayed — the partial output is
reported instead of duplicated.

---

## Configuration

Everything lives under `opencode.*` (see `application.yml`):

```yaml
opencode:
  base-url: https://opencode.ai/zen/v1
  api-key: ""                    # Zen free tier needs none
  default-provider: zen
  default-model: big-pickle
  stable-session-id: my-app-seed  # hashed into x-opencode-session
  include-official-tools: true    # keep on for the gateway
  force-streaming: true           # the gateway serves streaming only
  max-tokens: 32000
  max-tool-rounds: 12
  tool-result-limit: 4000
  connect-timeout-seconds: 30
  request-timeout-seconds: 300
  stream-idle-timeout-seconds: 300
  retry: { max-attempts: 3, backoff-millis: 1000, dns-backoff-millis: 1500 }
  providers:                      # any OpenAI-compatible endpoint
    deepseek:
      base-url: https://api.deepseek.com/v1
      api-key: ${DEEPSEEK_API_KEY:}
      default-model: deepseek-chat
```

`opencode.enabled=false` removes the whole library from the context.

---

## Adding your own tools

Any `ToolHandler` bean is advertised to the model and executed by the loop:

```java
@Bean
ToolHandler weatherTool() {
    return new ToolHandler() {
        public String name()        { return "weather"; }
        public String description() { return "Current weather for a city"; }
        public JsonNode parameters() {
            return ToolHandler.spec("weather", "Current weather for a city",
                    Map.of("city", "City name, e.g. Bengaluru")).getParameters();
        }
        public String execute(JsonNode args) throws Exception {
            return mapper.writeValueAsString(fetch(args.path("city").asText()));
        }
    };
}
```

Failures never break the loop: exceptions and `{"error": …}` / `{"ok": false}` results are handed
back to the model as-is so it can adapt. Results longer than `tool-result-limit` are truncated.

The demo app ships three examples — `current_time`, `calculate` (a dependency-free arithmetic
parser, no script engine) and `http_fetch`.

---

## Built-in coding tools

The gateway's free tier requires the application to advertise the eleven official CLI tool names
(`bash`, `edit`, `glob`, `grep`, `read`, `skill`, `task`, `todowrite`, `webfetch`, `websearch`,
`write`). Six of them are now **implemented locally in Java**, so the model can actually use them
instead of receiving `unknown tool`:

| Tool | What it does | Confined? |
|---|---|---|
| `read` | Read a text file (numbered lines); lists a directory when given one | workspace |
| `glob` | Find files by pattern, e.g. `**/*.java` | workspace |
| `grep` | Regex search with paths, line numbers and previews | workspace |
| `write` | Create a file or replace its contents | workspace |
| `edit` | Exact-text replacement (must be unique unless `replaceAll`) | workspace |
| `bash` | Run a shell command, capturing combined output | workspace cwd |
| `todowrite` | Maintain a structured task list for the session (validated, per-session) | — |
| `webfetch` | GET a URL as text / markdown / HTML (small built-in converter) | — |
| `skill` | Load a skill's `SKILL.md` instructions into the conversation | skills dir |
| `task` | Launch a sub-agent: a nested conversation with its own tool loop | depth-limited |

`websearch` is the one official name still **advertised but not implemented** — it needs a search
provider, and none is configured. `GET /api/tools` reports the two sets separately:
`executable` (a call will run) and `advertisedOnly` (names sent to the gateway without a local
implementation), so this can never be confused again.

### The task sub-agent

`task` runs `ChatService.nested(depth)` with a **fresh session id**, so the sub-agent's tool calls
never enter the parent's history — the parent sees one call in, one answer out. Nesting stops at
`opencode.tools.max-task-depth` (default 2), so a `task` that calls `task` cannot recurse forever.

### Skills

`skill` reads `opencode.tools.skills-dir`, expecting one directory per skill, each with a
`SKILL.md` whose YAML frontmatter supplies `name` and `description`. Blank disables the tool. The
available names are listed in the tool description, so the model knows what it may ask for.

### The workspace boundary

Every file tool resolves its path through `Workspace`, which canonicalises the target and refuses
anything outside the root — so `..` traversal and symlinks cannot escape. Absolute host paths are
**never honoured**: `/etc/passwd` is read as `<workspace>/etc/passwd`.

The root is `opencode.tools.workspace`, or **the process working directory when blank**, so the
application stays portable — start the jar anywhere and the agent's reach follows it.

### Consent: auto / ask / off

The voice page offers three modes, sent per request as `toolMode`:

| Mode | Behaviour |
|---|---|
| `auto` | Tools run without asking — **except `bash`**, which still prompts |
| `ask` | The stream pauses on a `tool_approval` event until the browser answers |
| `off` | Every call is refused with `{"error":"denied: …"}` |

In `ask` mode the loop emits:

```
event: tool_approval
data: {"id":"apr_…","name":"bash","arguments":"{\"command\":\"ls\"}"}
```

and waits (up to `opencode.tools.approval-timeout-seconds`) for:

```bash
curl -X POST localhost:8080/api/tool-approval \
     -H 'Content-Type: application/json' -d '{"id":"apr_…","allow":true}'
```

An unknown or expired id returns `404`; a timeout denies the call and emits `tool_approval_timeout`.
Set `opencode.tools.allow-shell-without-approval=true` to let `bash` run unprompted in `auto` mode —
off by default, because a command can do anything the application's user can.

---

## Using the library in your own app

```xml
<dependency>
  <groupId>com.pawwork.opencode</groupId>
  <artifactId>opencode-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```java
@RestController
class MyController {
    private final ChatService chat;      // tool loop + sessions
    private final OpenAiClient client;   // raw wire client

    @PostMapping("/ask")
    String ask(@RequestBody String question) {
        return chat.chat(null, null, null, question).getReply();
    }
}
```

`ChatService` (sessions + tool loop), `OpenAiClient` (raw OpenAI wire, streaming or not),
`ToolRegistry`, `OpenCodeIdentity` and `OpenCodeToolSchemas` are all beans, each backing off if you
declare your own.

---

## Tests

```bash
./mvnw test                     # unit tests, no network
./mvnw test -Dopencode.test.live=true   # also runs a live gateway call
```

Covered: session/request id shapes, SSE accumulation (null fragments, usage merging, reasoning
fallback), tool registry error handling, provider resolution, **OpenAI request passthrough**
(`stop`/`seed`/`response_format`/unknown fields survive serialisation), **undeclared-tool
detection**, and the OpenAI **error-type mapping**. A live gateway call runs only with
`-Dopencode.test.live=true`.

---

## Working with OpenClaw

[OpenClaw](https://docs.openclaw.ai) is an OpenAI-compatible client, so it plugs straight into the
`/v1` surface as a custom provider — no code changes, only config.

```bash
# 1. install (Node 24.16+)
npm install -g --prefix ~/.npm-global openclaw@latest --allow-scripts=openclaw
export PATH=~/.npm-global/bin:$PATH

# 2. point it at this service (non-interactive)
openclaw onboard --non-interactive --accept-risk \
  --auth-choice custom-api-key \
  --custom-base-url http://127.0.0.1:8080/v1 \
  --custom-provider-id engtop --custom-model-id big-pickle \
  --custom-api-key not-needed --custom-compatibility openai --custom-text-input \
  --gateway-bind loopback --gateway-port 18789 \
  --no-install-daemon --skip-daemon --skip-channels --skip-search --skip-skills --skip-hooks --skip-ui

# 3. run the Gateway and talk to it
openclaw gateway run &
openclaw agent -m "Reply with exactly: PONG" --json     # -> finalAssistantVisibleText: "PONG"
```

What that writes into `~/.openclaw/openclaw.json`:

```json5
{
  models: { providers: { engtop: {
    baseUrl: "http://127.0.0.1:8080/v1",
    api: "openai-completions",          // OpenAI chat-completions adapter
    apiKey: "not-needed",               // /v1 accepts and ignores Authorization
    models: [{ id: "big-pickle", contextWindow: 128000, maxTokens: 4096 }]
  }}},
  agents: { defaults: { model: { primary: "engtop/big-pickle" } } }
}
```

Verified live: plain turns (`winnerProvider: engtop`, `result: success`) **and** tool-using turns —
OpenClaw's agent declared its own tools, the model's `tool_calls` came back naming only those, and
the agent completed the task. The Control UI answers on `http://127.0.0.1:18789/`. Since this
service ignores `Authorization`, `apiKey` is a placeholder; put it in front of other clients with
whatever gateway auth you prefer.

---

## Project layout

```
opencode-spring-boot/
├── pom.xml                             parent (Spring Boot 3.5.16)
├── opencode-spring-boot-starter/
│   └── src/main/java/com/pawwork/opencode/
│       ├── client/      OpenAiClient, SseAccumulator, OpenCodeIdentity, OpenCodeException
│       ├── config/      OpenCodeProperties, ProviderProfile
│       ├── model/       ChatRequest/Response/Chunk, ChatMessage, ToolCall, ChatResult, …
│       ├── tools/       ToolHandler, ToolRegistry, DeclaredTools, OpenCodeToolSchemas, OfficialOpenCodeTools
│       ├── service/     ChatService (the tool loop)
│       └── autoconfigure/  OpenCodeAutoConfiguration
└── opencode-demo-app/
    └── src/main/java/com/pawwork/opencode/demo/
        ├── api/         ChatController, CompletionsController, AudioController
        ├── dto/         ChatApiRequest/Reply, ProviderInfo
        ├── config/      OpenApiConfig, GatewayExceptionHandler
        └── tools/       SampleTools, Arithmetic
```

Alongside the project, `../stt/` holds the local speech-to-text sidecar that
`AudioController` calls (faster-whisper model + `start-stt.sh` + `README.md`).
