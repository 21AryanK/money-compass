# Money Compass

Adaptive financial literacy assessment with a deterministic scoring core and
LLM-written explanations. Built for an Indian retail context: INR, and
instruments like PPF, NPS, ELSS, index funds and FDs.

Educational content only. Not financial advice.

## Status

| Phase | | |
|---|---|---|
| 0 | Repository and local infrastructure | done |
| 1 | Backend skeleton, persistence, auth | done |
| 2 | Adaptive questionnaire engine | not started |
| 3 | Spring AI integration, provider switching | not started |
| 4 | Literacy score | not started |
| 5 | Bedrock fallback, hardened | not started |
| 6 | Risk and capacity module | not started |
| 7 | Angular frontend | done, verified building |
| 8 | Tests, observability, deployment | not started |
| 9 | Documentation and demo | not started |

## Stack

| Layer | Choice | Why this version |
|---|---|---|
| Language | Java 21 | Spring AI 2.x requires 21 for development |
| Backend | Spring Boot 4.1.1 | latest stable, released 2026-08-21; only 4.0 and 4.1 are in open source support |
| AI | Spring AI 2.0.1 | released 2026-08-21; the 2.x line targets Spring Boot 4.x, the 1.1.x line is pinned to Boot 3.5.x |
| Resilience | Resilience4j 2.4.0 | the `-spring-boot4` artifact; `-spring-boot3` does not work on Boot 4 |
| Database | PostgreSQL 16, Flyway migrations | |
| Frontend | Angular 22 | released 2026-06-03 |
| Auth | Spring Security 7 resource server, HS256 JWT, 24h | no third-party JWT library to keep patched |

## Run locally in five commands

```bash
cp infra/.env.example infra/.env      # 1
openssl rand -base64 48               # 2  paste into JWT_SECRET in infra/.env
cd infra && docker compose up -d postgres ollama   # 3
./ollama-pull.sh                      # 4  several GB on first run
cd ../backend && ./mvnw spring-boot:run            # 5
```

Verify:

```bash
curl localhost:11434/api/tags          # lists the pulled model
curl localhost:8080/actuator/health    # {"status":"UP"}
```

If you do not have a Maven wrapper yet, generate one once with
`mvn -N wrapper:wrapper` inside `backend/`, or just use `mvn` in place of
`./mvnw`.

## Configuration

Everything is in `infra/.env`. Nothing else needs editing to point at a
different database, model or provider. See **[docs/configuration.md](docs/configuration.md)**
for what each variable does, where to get the value, and what breaks when it is
wrong.

## Try the auth flow

```bash
# register
curl -s -X POST localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"correct-horse-battery","profileType":"STUDENT"}'

# login
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"correct-horse-battery"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')

# 401 without the token
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/me

# 200 with it
curl -s localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
```

## Tests

```bash
cd backend && ./mvnw test
```

Integration tests use Testcontainers against a real Postgres 16, not H2. The
schema uses JSONB and `text[]`, neither of which H2 emulates faithfully, and a
test that passes on H2 but fails on Postgres is worse than no test. Docker must
be running.

## Design decisions

**Deterministic core, LLM at the edges.** Scores and allocations are computed in
plain Java. The model only writes explanations and, optionally, proposes one
clarifying follow-up question per session. This is what makes the score stable
across refreshes and testable without a model.

**Provider routing is application code.** Spring AI supplies interchangeable
`ChatModel` beans. The fallback ordering and the circuit breaker live in
`ResilientChatClient`. Fallback is not a Spring AI feature; say it that way.

**Enums are VARCHAR with CHECK constraints**, not native Postgres enum types.
Native enums need a custom Hibernate type and every value addition needs an
`ALTER TYPE` that cannot run inside a transaction. The constraint gives the
same integrity guarantee.

**Flyway owns the schema**, Hibernate runs with `ddl-auto: validate`. If the
mappings and the migrations disagree, the app refuses to start.

## Known limitations

Not implemented, by design: real market data, product-level recommendations,
tax computation, multi-currency. The app is educational.

## Costs

| | |
|---|---|
| Ollama | free; ~6 GB RAM for `llama3.1:8b`, 10 to 40s per response on CPU |
| OpenAI | fractions of a cent per session at an 800 token cap; budget under USD 5 for the whole build |
| Bedrock | no free tier; Llama 8B on-demand is comparable per session to `gpt-4o-mini` |

## Frontend

```bash
cd frontend
npm install
npm start          # http://localhost:4200
npm run build:prod
```

Angular 22, standalone components, signals, zoneless change detection, lazy
loaded routes. Verified building: 269 kB initial bundle, 72 kB transferred.

Three version constraints found the hard way, all pinned in `package.json`:

- Angular 22 requires TypeScript `>=6.0 <6.1`. Not 5.9.
- `@angular/build` 22.1 requires `vitest@^4.0.8`.
- npm 10.x crashes resolving this tree with `Cannot read properties of null
  (reading 'edgesOut')`. Use npm 12 or later.

Build time font inlining is disabled in `angular.json`. Inlining fetches from
fonts.googleapis.com during the build, which fails in any air gapped or
network restricted CI runner. The fonts load from the CDN at runtime instead,
with a system fallback in the stack.

The questionnaire, score and risk screens call the endpoints defined in section
5 of the specification. Those return 404 until Phases 2, 4 and 6 are built. Sign
in, registration and model status work against the current backend.
