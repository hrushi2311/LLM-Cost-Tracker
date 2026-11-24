# LLM Cost Tracker

A lightweight, self-hosted LLM cost tracker that discovers your agents automatically. One container plus Postgres, any language, no SDK.

> **Status: milestone 1 of 9 (skeleton).** The app starts, creates its database schema and reports health. The proxy, cost tracking and dashboard are not built yet. See [SPEC.md](SPEC.md) for the full design and build order.

## Quick start

Requires Docker with Compose v2.

```sh
cp .env.example .env        # then set DB_PASSWORD and TRACKER_ADMIN_PASSWORD (12+ chars)
docker compose up --build
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

## Configuration

Everything is configured through environment variables; see [.env.example](.env.example) for the full list with a comment on each.

| Variable | Default | Purpose |
|---|---|---|
| `DB_PASSWORD` | required | Postgres password (compose only) |
| `TRACKER_ADMIN_PASSWORD` | required | Dashboard login; the app refuses to start if empty or shorter than 12 characters |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db:5432/llmtracker` | Point at your own Postgres 16 to run only the `app` service |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `tracker` / required | Database credentials |
| `SERVER_PORT` | `8080` | HTTP port |

## Health

`/actuator/health` (overall), `/actuator/health/liveness` and `/actuator/health/readiness` (includes the database) are public. No other actuator endpoints are exposed.

## Development

Requires JDK 21 or newer and Docker (tests use Testcontainers for Postgres).

```sh
./gradlew :backend:test       # unit + integration tests
./gradlew :backend:bootJar    # backend/build/libs/llm-cost-tracker.jar
```

Design choices made during the build are recorded in [docs/DECISIONS.md](docs/DECISIONS.md).

## Licence

Apache-2.0 (LICENSE file to be added with the 0.1.0 release).
