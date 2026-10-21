# Sample calls & responses

Every payload below was captured live from the demo app (`http://localhost:8080`) talking to the
real OpenCode Zen gateway. Timestamps/ids differ per run.

Two surfaces:

* **`/v1`** — the OpenAI-spec API. This is what Cursor, LangChain, Claude Code (OpenAI mode) and
  any OpenAI SDK talk to.
* **`/api`** — the library's own extras (sessions + server-side tool loop).

**Session ids** (only `/api`): copy `sessionId` out of the response and send it back with the next
turn — that is how the library keeps the conversation (and its tool history) alive.

---

## 1. `POST /v1/chat/completions` — OpenAI spec, non-streaming

```bash
curl -s localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"big-pickle","messages":[{"role":"user","content":"Reply with exactly: PONG"}]}'
```

**Response — `200 OK`, `application/json`**

```json
{
  "id": "chatcmpl-070558e16dfe64e8cb5246692d0d9345",
  "object": "chat.completion",
  "created": 1790322145,
  "model": "big-pickle",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "PONG",
        "reasoning_content": "We need answer exactly PONG. No need tools. Ensure no extra."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 5785, "completion_tokens": 19, "total_tokens": 5804 }
}
```

Underneath, the gateway only serves streaming calls, so the library opened an SSE stream and
folded the chunks back together — the caller never has to know. Ids are normalised to
`chatcmpl-…`, and a request with **no `tools`** additionally carries `tool_choice:"none"` upstream
so the answer can only be content.

---

## 2. `POST /v1/chat/completions` with `"stream": true`

```bash
curl -sN localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"big-pickle","stream":true,
       "messages":[{"role":"user","content":"Count 1 to 3, comma separated."}]}'
```

**Response — `200 OK`, `text/event-stream`** (6 frames, all sharing one id)

```
data: {"id":"chatcmpl-070559466af77d15c9e186a7df5f4e0d","object":"chat.completion.chunk","created":1790322246,"model":"big-pickle","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"We need answer count"}}]}

data: {"id":"chatcmpl-070559466af77d15c9e186a7df5f4e0d","object":"chat.completion.chunk","created":1790322246,"model":"big-pickle","choices":[{"index":0,"delta":{"content":"1, 2, 3"},"finish_reason":"stop"}]}

… (reasoning/content fragments elided) …

data: [DONE]
```

Verbatim OpenAI chunk shapes — every OpenAI SDK's streaming path works unchanged.

---

## 3. Tool calling — the client declares its own tools (Cursor agents)

```bash
curl -s localhost:8080/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"big-pickle",
       "messages":[{"role":"user","content":"Calculate 7*24*60*60"}],
       "tools":[{"type":"function","function":{
           "name":"calculate","description":"Math calculator",
           "parameters":{"type":"object","properties":{"expression":{"type":"string"}}}}}]}'
```

**Response — `200 OK`**

```json
{
  "id": "chatcmpl-070558ff7377bb32097a54588f78ebd8",
  "object": "chat.completion",
  "created": 1790322175,
  "model": "big-pickle",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "We need answer calculation. …",
        "tool_calls": [
          {
            "id": "call_function_9x5g914in9ih_1",
            "type": "function",
            "function": { "name": "calculate", "arguments": "{\"expression\": \"7*24*60*60\"}" },
            "index": 0
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": { "prompt_tokens": 5833, "completion_tokens": 90, "total_tokens": 5923 }
}
```

Only `calculate` — even though the gateway was sent all 11 official CLI tools as well (the free
tier refuses bodies without them). The same request with `"stream": true` yields the same
`tool_calls` as delta fragments.

---

## 4. The model reaches for a tool the client never declared

Same request as above, but the prompt asks for a tool that is not in `tools`:

```bash
curl -s localhost:8080/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"big-pickle",
       "messages":[{"role":"user","content":"Run `echo hi` using the bash tool. If you cannot use bash, reply exactly: BASH_UNAVAILABLE."}],
       "tools":[… calculate only …]}'
```

**Response — `200 OK`, 3.6 s**

```json
{
  "choices": [
    { "index": 0,
      "message": { "role": "assistant", "content": "BASH_UNAVAILABLE" },
      "finish_reason": "stop" }
  ]
}
```

The model asked for `bash`, the endpoint answered that call in-band ("not available on this
endpoint…"), and asked again — so the client only ever sees its own tool (here: none).
A streamed response does the same: role/reasoning/content frames are relayed live, tool-call
frames are held until their names have been checked.

---

## 5. `GET /v1/models` and `GET /v1/models/{model}`

```bash
curl -s localhost:8080/v1/models
# {"object":"list","data":[{"id":"claude-fable-5","object":"model","created":…,"owned_by":"opencode"}, …]}  (80 models)

curl -s localhost:8080/v1/models/big-pickle
# {"id":"big-pickle","object":"model","created":1790322246,"owned_by":"opencode"}

curl -s localhost:8080/v1/models/nope
# {"error":{"message":"The model 'nope' does not exist","type":"invalid_request_error"}}   HTTP 404
```

---

## 6. Errors — OpenAI's error object, always

```http
HTTP/1.1 401
Content-Type: application/json
```
```json
{
  "error": {
    "message": "API 401: Model no-such-model is not supported",
    "type": "authentication_error",
    "upstream": "{\"type\":\"error\",\"error\":{\"type\":\"ModelError\",\"message\":\"Model no-such-model is not supported\"}}"
  }
}
```

```bash
curl -s localhost:8080/v1/chat/completions -H 'Content-Type: application/json' \
     -d '{"model":"big-pickle"}'          # no messages
```
```json
{ "error": { "message": "API 400: Error from provider (Console): Upstream request failed: [invalid_request_error] invalid request",
             "type": "invalid_request_error" } }                    # HTTP 400
```

Malformed JSON body → `400` with `"The request body was not valid JSON. …"`.
Transport failures (DNS, timeout, dead stream) → `502` with `"network error: …"` or
`"SSE stream idle for more than 300s…"`.

`type` always follows OpenAI's vocabulary: `invalid_request_error` (400/404/409/422),
`authentication_error` (401), `permission_error` (403), `rate_limit_error` (429),
`api_error`/`server_error` (5xx). `upstream` is our own extra field and is ignored by SDKs.

---

## 7. `POST /api/chat` — the library's own API (sessions + server-side tool loop)

```bash
curl -s localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"How many seconds are in a week? Use the calculate tool.","systemPrompt":"You are terse."}'
```

**Response — `200 OK`**

```json
{
  "sessionId": "sess_d9a6b3d4b80249c49b3d",
  "provider": "zen",
  "model": "big-pickle",
  "reply": "604,800 seconds.",
  "rounds": 2,
  "toolExecutions": [
    {
      "name": "calculate",
      "arguments": "{\"expression\": \"7*24*60*60\"}",
      "result": "{\"expression\":\"7*24*60*60\",\"result\":604800.0}",
      "success": true,
      "durationMs": 0
    }
  ],
  "usage": { "prompt_tokens": 12087, "completion_tokens": 61, "total_tokens": 12148 },
  "historySize": 5
}
```

`rounds: 2` = the model asked for `calculate`, the library ran it, fed the result back, and the
model produced the answer. Send `"sessionId":"sess_…"` back for the next turn
(`historySize: 9` after two turns).

---

## 8. `POST /api/chat/stream` — server-sent events

```bash
curl -sN localhost:8080/api/chat/stream -H 'Content-Type: application/json' \
  -d '{"message":"What is 15 * 17? Use the calculate tool. One short line."}'
```

**Response — `200 OK`, `text/event-stream`**

```
event:start
data:{"model":"big-pickle","provider":"zen","sessionId":"sess_38464f8c617b4ca884f8"}

event:round
data:{"round":1}

event:reasoning
data:{"text":"We need answer use calculate tool, one short line."}

event:tool_start
data:{"name":"calculate","arguments":"{\"expression\": \"15 * 17\"}"}

event:tool_result
data:{"success":true,"result":"{\"expression\": \"15 * 17\",\"result\":255.0}","name":"calculate"}

event:round
data:{"round":2}

event:token
data:{"text":"15 * 17 = 255"}

event:done
data:{"sessionId":"sess_38464f8c617b4ca884f8","provider":"zen","model":"big-pickle",
      "reply":"15 * 17 = 255","rounds":2,"toolExecutions":[…],
      "usage":{"prompt_tokens":12086,"completion_tokens":56,"total_tokens":12142},
      "historySize":4}
```

Events: `start` → `round` → (`token` | `reasoning`)* → (`tool_start` `tool_result`)* → `done`,
or `error`. `start` always carries the `sessionId` you need for the next turn.

---

## 9. Other endpoints

```bash
curl -s localhost:8080/api/chat/{id}/history   # wire history incl. assistant/tool_calls/tool messages
curl -s localhost:8080/api/providers           # zen / deepseek / openai
curl -s localhost:8080/api/tools               # 3 local + the 11 official CLI tools
curl -s localhost:8080/v1/health
# {"status":"UP","library":"opencode-spring-boot","defaultProvider":"zen",
#  "defaultModel":"big-pickle","openCodeGateway":true}
```

---

## 10. What the library sends upstream (the "via opencode" part)

Every gateway call carries the OpenCode CLI identity:

```http
POST https://opencode.ai/zen/v1/chat/completions
Content-Type: application/json
x-opencode-session: ses_6f1c…            (26 hex — SHA-256 of opencode.stable-session-id)
x-opencode-client: cli
x-opencode-project: global
x-opencode-request: msg_9a3f…c7k2m…      (12 hex + 14 alnum, fresh per turn)
User-Agent: opencode/1.18.31
```

Body:

```json
{
  "model": "big-pickle",
  "messages": [ …the caller's messages, unchanged… ],
  "stream": true,
  "stream_options": { "include_usage": true },
  "max_tokens": 32000,
  "tool_choice": "none",                  // only when the caller declared no tools
  "temperature": 0.7,                     // whatever the caller sent, forwarded
  "tools": [
    { "type": "function", "function": { "name": "bash", … } },
    { "type": "function", "function": { "name": "edit", … } },
    { "type": "function", "function": { "name": "glob", … } },
    { "type": "function", "function": { "name": "grep", … } },
    { "type": "function", "function": { "name": "read", … } },
    { "type": "function", "function": { "name": "skill", … } },
    { "type": "function", "function": { "name": "task", … } },
    { "type": "function", "function": { "name": "todowrite", … } },
    { "type": "function", "function": { "name": "webfetch", … } },
    { "type": "function", "function": { "name": "websearch", … } },
    { "type": "function", "function": { "name": "write", … } },
    { "type": "function", "function": { "name": "calculate", … } }   // the caller's own tool
  ]
}
```

The 11 official names are mandatory (the free tier returns `403 FreeTierError` without them —
verified: no `tools` field → 403, caller tools only → 403, official set → 200). They never appear
in our responses unless the caller declared them.

---

## 11. From Java — the library API directly

```java
@RestController
class MyController {

    private final ChatService chat;      // sessions + tool loop
    private final OpenAiClient client;   // raw OpenAI wire, streaming or not

    MyController(ChatService chat, OpenAiClient client) {
        this.chat = chat;
        this.client = client;
    }

    @PostMapping("/ask")
    ChatApiReply ask(@RequestBody String question) {
        // Blocking: runs the whole model⇄tool loop, returns the final answer.
        return chat.chat(null, null, null, question).getReply();
    }

    @PostMapping("/ask/raw")
    ChatResponse raw() {
        ProviderProfile zen = properties.resolve("zen");
        ChatRequest request = new ChatRequest("big-pickle",
                List.of(ChatMessage.user("Reply with exactly: PONG")));
        return client.complete(zen, request);          // gateway dialect handled for you
    }
}
```

Streaming from Java:

```java
chat.chatStream(sessionId, "zen", "big-pickle", question, new ChatService.StreamListener() {
    public void onToken(String text)                    { out.print(text); }
    public void onToolStart(String name, String args)   { out.println("\n[tool] " + name + " " + args); }
    public void onDone(ChatResult result)               { out.println("\n" + result.getReply()); }
});
```

---

## 12. From Python — any OpenAI SDK against `/v1`

```python
from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/v1", api_key="not-needed")

print([m.id for m in client.models.list().data][:3])

stream = client.chat.completions.create(
    model="big-pickle",
    messages=[{"role": "user", "content": "Count 1 to 3"}],
    stream=True,
)
for chunk in stream:
    delta = chunk.choices[0].delta
    if delta and delta.content:
        print(delta.content, end="", flush=True)
```

With tools:

```python
tools = [{"type": "function", "function": {
    "name": "calculate", "description": "Math calculator",
    "parameters": {"type": "object", "properties": {"expression": {"type": "string"}}}}}]

answer = client.chat.completions.create(
    model="big-pickle",
    messages=[{"role": "user", "content": "Calculate 7*24*60*60"}],
    tools=tools,
)
call = answer.choices[0].message.tool_calls[0]
print(call.function.name, call.function.arguments)      # calculate {"expression": "7*24*60*60"}
```
