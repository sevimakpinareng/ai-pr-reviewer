# pr-reviewer

An AI code-review bot for GitHub pull requests.

**Status:** project skeleton only. Webhooks, GitHub integration, and LLM review
are intentionally *not* implemented yet — this commit just stands up a runnable
Spring Boot service with a database and migrations.

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

## Build

```bash
./mvnw clean verify
```

## Roadmap (not in this skeleton)

- GitHub App webhook ingestion
- GitHub API integration
- LLM-powered review generation
