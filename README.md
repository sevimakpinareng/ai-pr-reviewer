# pr-reviewer

[![CI](https://github.com/sevimakpinareng/ai-pr-reviewer/actions/workflows/ci.yml/badge.svg)](https://github.com/sevimakpinareng/ai-pr-reviewer/actions/workflows/ci.yml)

An AI code-review bot for GitHub pull requests: a signed webhook is queued
(RabbitMQ), a worker fetches the PR diff, reviews it file by file with an LLM
(Spring AI — local Ollama or Mistral), persists the findings (PostgreSQL), and
posts them back as a single GitHub review. Redis provides dedupe / superseding /
rate limiting, and metrics are exposed for Prometheus.

## Stack

- Java 21
- Spring Boot 3.4 (Web, Data JPA, Validation, Actuator)
- PostgreSQL 16
- Flyway (schema migrations)
- springdoc-openapi (Swagger UI)
- Maven (with wrapper)

## Project layout

```
src/main/java/com/example/prreviewer/
├── config/      # @Configuration beans (empty skeleton)
├── controller/  # REST controllers — HealthController
├── service/     # business logic (empty skeleton)
├── repository/  # Spring Data JPA repos (empty skeleton)
├── entity/      # JPA entities (empty skeleton)
└── dto/         # request/response DTOs (empty skeleton)
src/main/resources/
├── application.yml
└── db/migration/V1__init.sql   # Flyway migrations
```

## Configuration

The app reads its DB connection from environment variables (no secrets in the
repo). Copy the example file and adjust as needed:

```bash
cp .env.example .env
```

| Variable      | Meaning                          | Default                                        |
|---------------|----------------------------------|------------------------------------------------|
| `DB_URL`      | JDBC URL for Postgres            | `jdbc:postgresql://localhost:5432/prreviewer`  |
| `DB_USER`     | DB username                      | `prreviewer`                                   |
| `DB_PASSWORD` | DB password                      | `prreviewer`                                   |

## Run it

### 1. Start the database

```bash
docker compose up -d
```

This boots PostgreSQL on `localhost:5432` with a persistent named volume
(`pgdata`), so your data survives container restarts.

### 2. Run the application

```bash
./mvnw spring-boot:run
```

On startup Flyway applies `V1__init.sql`, creating the `installations` and
`repositories` tables.

## Endpoints

| Endpoint              | Purpose                                            |
|-----------------------|----------------------------------------------------|
| `GET /api/health`     | Simple app liveness check                          |
| `GET /actuator/health`| Spring Boot health (includes DB status)            |
| `GET /swagger-ui.html`| API documentation                                  |

## Build & Test

```bash
./mvnw verify
```

`verify` compiles, runs the unit tests, runs the integration + end-to-end tests
(which spin up **real** PostgreSQL, RabbitMQ, and Redis via Testcontainers — a
working Docker daemon is required), and produces a JaCoCo coverage report at
`target/site/jacoco/index.html`.

- **On standard Docker (Linux/CI):** just `./mvnw verify` — Testcontainers finds
  the Docker socket automatically.
- **On local macOS + colima:** add `-Pcolima` and point Docker at a space-free
  socket, because of quirks in that setup:

  ```bash
  ln -sf "$HOME/Library/Application Support/colima/default/docker.sock" /tmp/colima.sock
  DOCKER_HOST=unix:///tmp/colima.sock ./mvnw -Pcolima verify
  ```

  The `colima` profile is **local-only**; CI does not use it.

To run only the fast unit tests (skip the container-backed ones):

```bash
./mvnw test -DexcludedGroups=integration
```

## Continuous integration

Every push to `main` and every pull request runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml):
a fast **compile** job for quick feedback, then a **build** job that runs
`mvn verify` (build + unit + integration + coverage). Testcontainers runs
directly on the Ubuntu runner's Docker, and because the tests mock the external
edges (GitHub API and the LLM), **no secrets are needed**. Surefire and JaCoCo
reports are uploaded as build artifacts (even on failure), and a coverage
summary is written to the job summary. Dependabot ([`.github/dependabot.yml`](.github/dependabot.yml))
opens weekly PRs to keep Maven and Actions dependencies current.

## Roadmap (not in this skeleton)

- GitHub App webhook ingestion
- GitHub API integration
- LLM-powered review generation
