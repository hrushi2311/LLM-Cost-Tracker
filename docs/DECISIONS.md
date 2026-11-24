# Decisions

Choices made where SPEC.md was ambiguous or had to be adapted. Newest last.

## Milestone 1 – Skeleton

- **Spring Boot 3.5.16, Gradle 9.9.2.** "Spring Boot 3 (latest stable)" read as the newest 3.x release on Maven Central at build time (2026-11-24); Gradle is the current release.
- **Java 21 bytecode via `options.release = 21`, no toolchain.** A Gradle toolchain would require a local JDK 21 or an auto-provisioning plugin (an unlisted dependency). Compiling with `--release 21` on any JDK 21+ produces Java 21 bytecode and API checks; CI and the Docker build both use JDK 21.
- **No frontend stage in the Dockerfile yet.** `frontend/` does not exist until milestone 6, and `npm ci` fails without a `package.json`. The node stage from the spec is added with the frontend.
- **`bootJar` named `llm-cost-tracker.jar`, plain `jar` disabled,** so the Dockerfile copies one known file instead of a glob.
- **Readiness includes the database.** `/actuator/health/readiness` reports DOWN when Postgres is unreachable; liveness does not depend on it. Health details are hidden (`show-details: never`), since the endpoint is public.
- **Admin password validated at startup** with Bean Validation on `TrackerProperties` (empty or < 12 characters → startup fails). Added in milestone 1 because the compose file already requires the variable.
- **CI runs the compose stack.** Besides `./gradlew test`, the CI `docker` job builds the image, runs `docker compose up`, waits for the container healthcheck and counts the seven tables — the milestone 1 acceptance check, automated.
- **Healthcheck uses `wget`.** Checked on 2026-11-24: `eclipse-temurin:21-jre` (Ubuntu 26.04) ships `/usr/bin/wget` (but not `curl`), so the spec's healthcheck works without extra packages.
- **JVM default time zone pinned to UTC** in `main()` and in the test JVM. The spec stores all timestamps in UTC; also, pgjdbc sends the JVM zone on connect and Postgres 16 rejects legacy aliases such as `Asia/Calcutta`, which broke startup on such hosts. Budget periods will use `TRACKER_TIMEZONE` explicitly.
- **Compose image is `llm-cost-tracker:latest` for now.** The spec's `ghcr.io/OWNER/...` placeholder is rejected by Docker (repository names must be lowercase). Milestone 9 switches it to the real GHCR path once the owner and project name are settled.
- **Dependabot ignores major bumps of Spring Boot and `eclipse-temurin`.** The spec pins Spring Boot 3 and Java 21; Dependabot opened 3.5→4.2 and 21→25 PRs on the first run. Minor and patch updates still flow.
- **`.env` also passed to the app via `env_file` (optional)** so any `TRACKER_*` variable added to `.env` reaches the app without editing the compose file.
