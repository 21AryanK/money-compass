# Money Compass

Your go-to Financial Buddy that questions you and suggest you, how to make best out of your money according to your income, risk factor etc.

Educational content only. Not financial advice.

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

## What the app does

Everything the single-file prototype in `prototype/` does, backed by the real
API. The prototype's rules were ported to Java line for line, and
`PrototypeParityTest` replays 120 sessions the prototype itself produced to
prove the two agree.

| Screen | What's there |
|---|---|
| Sign in / register | Profile picked at registration; model status shown before sign-in |
| Dashboard | Latest score and band, change since last time, history, resume an unfinished assessment, change profile |
| Questionnaire | 61 questions with per-profile wording; "I don't know" on every question; simpler re-asks, follow-ups and skipped advanced questions, each explained; Back; live progress estimate; Wikipedia links |
| Analysis | Narrates the real results as they arrive, including which model wrote the explanation and how long it took |
| Score | Score ring, category breakdown ("n/a" where not assessed), money snapshot, AI explanation |
| Risk | Tolerance, itemised capacity, band meter, choose a different band to plan around, allocation, AI rationale |
| Investment plan | Monthly priorities, amount / horizon / step-up / vehicles, projection with range, after-fee-and-tax estimate, growth chart, instrument table, fund / sector / debt / gold / cash breakdowns, PDF report |
| Ask Compass | Chat about your own results on every results screen |

**How the AI parts work.**

- **Explanations:** the model writes them from facts the application computed
  (rupee figures included) and is told to use no other numbers.
- **Regenerate:** the earlier drafts go back to the model at a higher
  temperature, with an instruction to take a different angle. Every draft is
  kept, so you can page through ‹ 2 / 3 ›.
- **Feedback:** 👍 / 👎 is stored per draft.
- **Ask Compass:** the application works out the topic and a grounded answer
  first, so what-ifs are computed in Java, not by the model. The model then
  rewrites that answer conversationally. Stock and crypto tips are declined
  before any model is called.
- **Without a model:** if no model answers, explanations and replies are
  assembled from the same facts and labelled as such. Scores, bands and plans
  never depend on a model.

## Tests

```bash
cd backend && ./mvnw test
```

Unit tests need nothing running: the prototype parity test, the narrative and
assistant tests (with a mocked model), and formatting. To also check a real
model's structured output, set `MC_LIVE_OLLAMA_MODEL` (for example
`llama3.1:8b`); `LiveOllamaNarrativeTest` runs only then.

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

The screens and styling are the prototype's: its design system is
`src/styles.scss`, and each prototype screen is a lazy-loaded route inside
`shell/`. The PDF report uses jsPDF, loaded only on the first download.

## Using a different Ollama install

Everything about where Ollama lives is the one variable in `infra/.env`:

```
OLLAMA_BASE_URL=http://localhost:11434
```

Point it at wherever your Ollama is actually running - a different port, a
machine on your LAN, a remote box - and restart the backend. Nothing else in
the app needs editing. `docker-compose.yml` overrides this to `http://ollama:11434`
for the backend *container* specifically, so if you run the backend with
`./mvnw spring-boot:run` on your host instead, `infra/.env`'s value is the one
that applies. `GET /api/health/ai` (once signed in) reports whether the
configured URL is actually reachable and which model is on it, which is the
fastest way to confirm a new path works before running the questionnaire.
