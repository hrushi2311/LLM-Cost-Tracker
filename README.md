# LLM Cost Tracker

A lightweight, self-hosted LLM cost tracker that discovers your agents automatically. One container plus Postgres, any language, no SDK.

> **Status: milestone 5 of 9.** The proxy forwards OpenAI, Anthropic and custom OpenAI-compatible traffic, records tokens and cost for OpenAI Chat Completions, OpenAI Responses and Anthropic Messages (streaming and non-streaming), and attributes every call to an agent and an optional run. The dashboard, budgets and seeded prices come in later milestones. See [SPEC.md](SPEC.md) for the full design and build order.

## Quick start

Requires Docker with Compose v2.

```sh
cp .env.example .env        # then set DB_PASSWORD and TRACKER_ADMIN_PASSWORD (12+ chars)
docker compose up -d --build
curl http://localhost:8080/actuator/health   # {"status":"UP",...}
```

### Create a project key

Until the dashboard exists (milestone 6), create projects from the command line. The key is printed once; only its hash is stored.

```sh
docker compose run --rm app --create-project=my-app
# Created project "my-app" (id 6f1c...).
# Project key, shown only once. Send it as the X-Tracker-Key header:
# trk_4Xq...
```

Outside Docker: `java -jar llm-cost-tracker.jar --create-project=my-app` with the same database settings.

### Point your app at the proxy

Change the base URL and add the project key header. Your provider API key stays in your app and is passed through untouched.

```python
# Python (openai)
client = OpenAI(base_url="http://localhost:8080/proxy/openai",
                default_headers={"X-Tracker-Key": "trk_..."})
```

```java
// Java (openai-java)
OpenAIClient client = OpenAIOkHttpClient.builder()
        .fromEnv()
        .baseUrl("http://localhost:8080/proxy/openai")
        .putHeader("X-Tracker-Key", "trk_...")
        .build();
```

```js
// Node (openai)
const client = new OpenAI({ baseURL: "http://localhost:8080/proxy/openai",
                            defaultHeaders: { "X-Tracker-Key": "trk_..." } });
```

Anthropic works the same way; `anthropic-version`, `anthropic-beta` and every other header pass through unchanged.

```python
# Python (anthropic)
client = Anthropic(base_url="http://localhost:8080/proxy/anthropic",
                   default_headers={"X-Tracker-Key": "trk_..."})
```

```java
// Java (anthropic-java)
AnthropicClient client = AnthropicOkHttpClient.builder()
        .fromEnv()
        .baseUrl("http://localhost:8080/proxy/anthropic")
        .putHeader("X-Tracker-Key", "trk_...")
        .build();
```

```js
// Node (@anthropic-ai/sdk)
const client = new Anthropic({ baseURL: "http://localhost:8080/proxy/anthropic",
                               defaultHeaders: { "X-Tracker-Key": "trk_..." } });
```

| Provider | Proxy base URL | Forwards to |
|---|---|---|
| `openai` | `http://host:8080/proxy/openai` | `TRACKER_OPENAI_BASE_URL` (default `https://api.openai.com/v1`) |
| `anthropic` | `http://host:8080/proxy/anthropic` | `TRACKER_ANTHROPIC_BASE_URL` (default `https://api.anthropic.com`) |
| any name you configure | `http://host:8080/proxy/{name}` | the URL given in `TRACKER_CUSTOM_PROVIDERS` |

### Custom OpenAI-compatible providers

Any service that speaks the OpenAI API (Groq, Together, Ollama, vLLM, LM Studio and so on) can be added by name:

```sh
TRACKER_CUSTOM_PROVIDERS=groq=https://api.groq.com/openai/v1,local=http://ollama:11434/v1
```

Point the OpenAI SDK at `http://host:8080/proxy/groq`. Calls are parsed like OpenAI's and stored with `provider = groq`, so they are priced from `groq` rows in the price table. The proxy only ever calls the configured URL. Names must be lowercase (`a-z`, `0-9`, `-`), and a malformed entry stops the app at startup.

### Project key in the URL (when you can only change the base URL)

Some tools let you set a base URL but not extra headers. For those, put the project key in the path instead:

```
OPENAI_BASE_URL=http://localhost:8080/proxy/k/trk_.../openai
```

The `X-Tracker-Key` header is still the recommended option: URLs end up in more places (shell history, client-side logs, proxies) than headers do. The tracker masks the key in every log line it writes, including the access log, and never forwards it upstream. If both are given, the header wins.

## Agents, runs and tags

Every recorded call belongs to exactly one agent, found in this order (first match wins):

1. **Header:** `X-Tracker-Agent: researcher`.
2. **URL path:** `http://host:8080/proxy/openai/agent/researcher` as the base URL.
3. **Fingerprint:** a SHA-256 hash of the system prompt (Chat Completions `system`/`developer` messages, Responses `instructions`, Anthropic `system`). The agent is named `agent-a3f9` from the first hex characters, and calls with the same system prompt land on the same agent whatever the user message. Only the hash is stored, never the prompt.
4. Otherwise the agent named `unattributed`.

Agents are created on first sight; there is nothing to register. Names are normalised to lowercase slugs (`Customer Support Bot` becomes `customer-support-bot`). Set `TRACKER_FINGERPRINT_MASK=true` if your system prompts are templates: numbers, UUIDs, ISO dates and email addresses are then replaced before hashing, so `Customer 1042 …` and `Customer 77 …` stay one agent.

Agents can be renamed (display name only; the slug keeps matching) and merged. A merge moves the old agent's calls to the new one and records its name and fingerprint as aliases, so later calls follow automatically. Both arrive in the dashboard in milestone 6.

**Runs:** send `X-Tracker-Run-Id: <id>` (up to 128 printable characters) on every call of one task to group them, across agents.

**Tags:** `X-Tracker-Tag-<key>: <value>` adds free-form tags (up to 10 per call; key up to 32, value up to 128 characters). Invalid or extra tags are ignored.

All `X-Tracker-*` headers are removed before the request is forwarded.

## What gets recorded

| Endpoint | Recorded from |
|---|---|
| OpenAI Chat Completions (`POST …/chat/completions`), streaming and non-streaming | yes |
| OpenAI Responses (`POST …/responses`), streaming and non-streaming | yes |
| Anthropic Messages (`POST /v1/messages`), streaming and non-streaming | yes |
| The same paths on custom OpenAI-compatible providers | yes |

All other paths are forwarded unchanged and not recorded. For each recorded call the tracker stores the agent, run ID, tags, model, uncached input, output, cache-read, cache-write and reasoning tokens, cost, status code and latency, and nothing else.

Token counts are normalised so the same columns mean the same thing for every provider. OpenAI reports input including cached tokens, so the cached part is subtracted. Anthropic's `input_tokens` already excludes cache reads and writes, so it is stored as reported. Reasoning and thinking tokens are already counted in output; they are stored for display and never charged twice.

Cost uses the `model_price` table: the row in effect on the call's date, matching the exact model name first, then the longest stored prefix (so `gpt-4o-2024-08-06` is priced as `gpt-4o`). A model with no price is stored with a null cost and can be priced later. The price table ships empty until milestone 8, so add rows yourself for now.

**Known limitations:** streamed Chat Completions add one extra final chunk unless you set `stream_options` yourself (see Streaming). Anthropic charges more to write a 1-hour cache entry (2× input) than a 5-minute one (1.25× input). v0.1 uses a single cache-write price per model, so 1-hour cache writes are under-costed. Batch, long-context and regional price tiers are not modelled either.

Recording happens after the response has been sent and never delays or fails a call. If the in-memory queue is full or Postgres is down, events are dropped and counted in the `tracker.events.dropped` metric.

### Streaming

Streamed responses (server-sent events) are passed to your app chunk by chunk as they arrive, unchanged; nothing is buffered. The tracker reads usage from a copy of each chunk while keeping only a small fixed window (128 KB) of the current event, however long the stream is:

- **OpenAI Chat Completions:** usage is only sent when the request has `stream_options.include_usage: true`. If your request does not set `stream_options` at all, the tracker adds `"stream_options":{"include_usage":true}` and changes nothing else in the request. **Your app then receives one extra final chunk before `data: [DONE]`, with an empty `choices` array and the `usage` for the whole request.** The official SDKs handle this chunk; code that reads `choices[0]` from every chunk without checking should skip chunks with empty `choices`. Turn this off with `TRACKER_OPENAI_INJECT_STREAM_USAGE=false`. If you set `stream_options` yourself, even to `include_usage: false`, the request is left untouched. Without usage the call is recorded as incomplete, with zero tokens.
- **OpenAI Responses:** usage comes from the `response.completed` event.
- **Anthropic Messages:** input and cache tokens come from `message_start`; output tokens come from the last `message_delta`, whose counts are cumulative.

If your app disconnects mid-stream, the tracker closes the upstream request too, so the provider stops generating (and billing), and records the call as incomplete with whatever usage it had seen. If the provider sends an error event or drops the connection mid-stream, your app receives exactly that, and the call is recorded as incomplete.

## Privacy

- **Never stored or logged:** prompts, completions, system prompts, and request or response bodies.
- **Never stored or logged:** provider API keys (`Authorization`, `x-api-key`) and project keys. Log output passes through a redacting filter as a safety net.
- **Never forwarded:** `X-Tracker-*` headers are removed before forwarding.
- **Never stored or logged:** system prompts. Only their SHA-256 fingerprint is kept.
- **Never logged:** a project key given in the URL (`/proxy/k/{key}/…`). It is masked in application, error and access logs.
- **Stored:** counts, costs, timings, model names, agent names, run IDs and tags you send, system-prompt fingerprints, and the SHA-256 hash plus last 4 characters of each project key.

## Configuration

Everything is configured through environment variables; see [.env.example](.env.example) for the full list with a comment on each.

| Variable | Default | Purpose |
|---|---|---|
| `DB_PASSWORD` | required | Postgres password (compose only) |
| `TRACKER_ADMIN_PASSWORD` | required | Dashboard login; the app refuses to start if empty or shorter than 12 characters |
| `TRACKER_REQUIRE_PROJECT_KEY` | `true` | Reject proxy calls without a valid `X-Tracker-Key` (401). Never expose the proxy publicly with this off |
| `TRACKER_OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI upstream |
| `TRACKER_ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | Anthropic upstream |
| `TRACKER_CUSTOM_PROVIDERS` | empty | `name=url` pairs, comma separated, for OpenAI-compatible providers |
| `TRACKER_CONNECT_TIMEOUT` | `10s` | Upstream connect timeout |
| `TRACKER_UPSTREAM_TIMEOUT` | `10m` | Upstream response timeout |
| `TRACKER_MAX_REQUEST_BODY` | `20MB` | Largest accepted request body (413 above it) |
| `TRACKER_QUEUE_CAPACITY` | `10000` | Usage event queue size |
| `TRACKER_OPENAI_INJECT_STREAM_USAGE` | `true` | Add `stream_options.include_usage` to streamed Chat Completions that do not set `stream_options` (adds one final chunk) |
| `TRACKER_FINGERPRINT_ENABLED` | `true` | Discover agents from a system-prompt fingerprint |
| `TRACKER_FINGERPRINT_MASK` | `false` | Mask numbers, UUIDs, ISO dates and emails before fingerprinting |
| `TRACKER_ACCESS_LOG` | `false` | HTTP access log (project keys in paths are masked) |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db:5432/llmtracker` | Point at your own Postgres 16 to run only the `app` service |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `tracker` / required | Database credentials |
| `SERVER_PORT` | `8080` | HTTP port |

Run the tracker behind a TLS-terminating reverse proxy for anything beyond localhost.

## Health

`/actuator/health` (overall), `/actuator/health/liveness` and `/actuator/health/readiness` (includes the database) are public. No other actuator endpoints are exposed.

## Development

Requires JDK 21 or newer and Docker (tests use Testcontainers for Postgres and MockWebServer in place of the providers; no real API keys are used).

```sh
./gradlew :backend:test       # unit + integration tests
./gradlew :backend:bootJar    # backend/build/libs/llm-cost-tracker.jar
```

Design choices made during the build are recorded in [docs/DECISIONS.md](docs/DECISIONS.md).

## Licence

Apache-2.0 (LICENSE file to be added with the 0.1.0 release).
