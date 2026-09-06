# Configuration reference

Everything configurable lives in **one file**: `infra/.env`. Copy the template
and fill in the blanks.

```bash
cp infra/.env.example infra/.env
```

Two things read that file:

| Consumer | How |
|---|---|
| `docker compose` | `env_file: .env` in `infra/docker-compose.yml` |
| the Spring app | `spring.config.import: optional:file:../infra/.env[.properties]` in `application.yml` |

The second line is why `./mvnw spring-boot:run` and `docker compose up` both
see identical settings without you exporting anything by hand.

**Format rules.** Values are read literally.
- No quotes: `JWT_SECRET=abc123`, not `JWT_SECRET="abc123"`
- No trailing comments: `AWS_REGION=us-east-1 # mumbai` puts `us-east-1 # mumbai` into the value
- A `#` inside a value needs no escaping as long as it is not preceded by whitespace

---

## Quick reference: what has to be filled in

| Variable | `local` profile | `prod` profile |
|---|---|---|
| `JWT_SECRET` | required | required |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | defaults work | required |
| `OPENAI_API_KEY` | not used | required |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | not used | required unless on an instance role |
| everything else | defaults work | defaults work |

Running locally, the only thing you must generate is `JWT_SECRET`.

---

## 1. Runtime profile

```
SPRING_PROFILES_ACTIVE=local
```

| Value | Primary model | Fallback model | Credentials needed |
|---|---|---|---|
| `local` | Ollama | Ollama, different system prompt | none |
| `prod` | OpenAI | AWS Bedrock Converse | OpenAI key + AWS credentials |

`local` uses Ollama for both sides on purpose: it means the fallback path in
`ResilientChatClient` is exercisable while you develop, without a cloud bill.

---

## 2. Database

### Option A: the bundled container

Nothing to do. `docker compose up postgres` creates a Postgres 16 with the
credentials already in `.env`, and the defaults in `DB_URL` point at it.

### Option B: hosted Postgres

Providers hand you a URI in this shape:

```
postgresql://USER:PASSWORD@HOST:5432/DBNAME?sslmode=require
```

Split it into three variables. Two things trip people up: the `jdbc:` prefix,
and the fact that credentials are **not** part of `DB_URL`.

```
DB_URL=jdbc:postgresql://ep-cool-frost-123.ap-southeast-1.aws.neon.tech:5432/moneycompass?sslmode=require
DB_USER=moneycompass_owner
DB_PASSWORD=npg_xxxxxxxxxxxx
```

Where to find the connection details:

| Provider | Console path |
|---|---|
| Neon | https://console.neon.tech → project → Connection Details |
| Supabase | https://supabase.com/dashboard → Project Settings → Database |
| Render | https://dashboard.render.com → your Postgres → Connections |
| AWS RDS | https://console.aws.amazon.com/rds → instance → Connectivity & security → Endpoint |

Then either drop the `postgres` service from your compose command
(`docker compose up backend ollama`) or leave it running and simply unused.

**Schema ownership.** Flyway creates and owns every table. Hibernate runs with
`ddl-auto: validate` and will refuse to start if the entity mappings and the
migrations disagree. Never let Hibernate generate the schema; the migration
history and the live schema would drift apart silently.

---

## 3. JWT secret

HS256 needs at least 32 bytes. `MoneyCompassProperties` validates this at
startup, so a short secret fails the context refresh with a readable message
rather than throwing a cryptographic error on the first login.

```bash
openssl rand -base64 48
```

Paste the output into `JWT_SECRET`. `infra/.env` is gitignored; keep it that
way. Rotating the secret invalidates every issued token, which is the intended
behaviour and, with a 24h TTL and no refresh token, is not disruptive.

---

## 4. Provider routing

```
AI_PRIMARY=ollama      # ollama | openai
AI_FALLBACK=ollama     # bedrock | ollama | none
```

These are read by application code, not by Spring AI. Spring AI supplies
interchangeable `ChatModel` beans; the ordering, the circuit breaker and the
retry policy are all in `ResilientChatClient` (Phase 5). Worth stating
precisely in interviews: **provider fallback is not a Spring AI feature.**

The profile files set sensible defaults, so you only touch these two variables
to test an unusual combination.

---

## 5. Ollama

```
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=llama3.1:8b
```

`OLLAMA_BASE_URL` differs by where the caller runs. `docker-compose.yml`
overrides it to `http://ollama:11434` for the backend container; the value in
`.env` is the one used when you run the backend on your host.

Pull the model after the container is up:

```bash
cd infra && ./ollama-pull.sh
```

The script reads `OLLAMA_MODEL` from `.env`, so there is one place to change it.
`llama3.1:8b` needs roughly 6 GB of RAM at 4-bit quantisation. Below that,
switch to `llama3.2:3b` in `.env` and re-run the script. On CPU, expect 10 to
40 seconds per response.

---

## 6. OpenAI

```
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-4o-mini
```

| What | Where |
|---|---|
| Create a key | https://platform.openai.com/api-keys |
| Add credit (do this first) | https://platform.openai.com/settings/organization/billing |
| Model list and pricing | https://platform.openai.com/docs/models |

A key on an account with no credit returns 429 on every call, which the circuit
breaker will read as a primary failure. That is correct behaviour, but if you
are seeing constant Bedrock fallbacks, check billing before you debug the code.

---

## 7. AWS Bedrock

```
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=
BEDROCK_MODEL=meta.llama3-1-8b-instruct-v1:0
```

Three things must all be true before the first call succeeds. Missing any one
of them produces `AccessDeniedException`, which does not distinguish between
them.

**1. Model access is granted, per region.**

https://console.aws.amazon.com/bedrock/home#/modelaccess

This is a manual approval step and by far the most common cause of a failing
first call. Request access to the Meta Llama family and wait for the status to
read "Access granted". Access is granted per region, so requesting it in
`us-east-1` does nothing for `ap-south-1`.

**2. The model ID exists in your region.**

Availability differs by region and model IDs change as new versions ship. Check
the current list rather than trusting the default above:

https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html

`us-east-1` has the broadest Llama coverage. If you are in India and want lower
latency, check `ap-south-1` against that page first.

**3. The credentials can invoke it.**

Create an IAM user at https://console.aws.amazon.com/iam/home#/users, then
Security credentials → Create access key. Attach this policy, with the ARN
narrowed to the one model you actually use:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "InvokeOneBedrockModel",
      "Effect": "Allow",
      "Action": [
        "bedrock:InvokeModel",
        "bedrock:InvokeModelWithResponseStream",
        "bedrock:Converse",
        "bedrock:ConverseStream"
      ],
      "Resource": "arn:aws:bedrock:us-east-1::foundation-model/meta.llama3-1-8b-instruct-v1:0"
    }
  ]
}
```

Replace the region in the ARN if you change `AWS_REGION`. The empty account
segment (`bedrock:us-east-1::`) is correct for foundation models: they are not
account-scoped resources.

**On EC2, ECS or Lambda:** leave `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`
blank and attach the policy to an instance or task role instead. The AWS SDK
picks it up from the default credential chain. Long-lived access keys on a
server are a liability.

**Cost.** Bedrock has no free tier. Llama 8B on-demand is billed per 1K input
and output tokens, comparable per session to `gpt-4o-mini` at an 800 token cap.
Current pricing: https://aws.amazon.com/bedrock/pricing/

---

## 8. Application tuning

```
QUESTIONNAIRE_MAX_QUESTIONS=15
AI_TEMPERATURE=0.2
AI_MAX_TOKENS=800
```

Applied to both providers on every call. Temperature is low because the
narrative should be stable across refreshes; the numeric score never comes from
the model at all, so temperature has no effect on it.

---

## 9. Frontend and CORS

```
API_BASE_URL=http://localhost:8080
CORS_ALLOWED_ORIGINS=http://localhost:4200
```

`CORS_ALLOWED_ORIGINS` is a comma-separated list of exact origins. Scheme, host
and port must match; no wildcards, no trailing slash. Add your deployed
frontend origin before you deploy, or the browser blocks every request with a
CORS error that looks like a backend outage.

```
CORS_ALLOWED_ORIGINS=http://localhost:4200,https://money-compass.vercel.app
```

---

## Where each variable ends up

Useful when a value is not taking effect and you need to trace it.

| Variable | Read by | Bound to |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `application.yml` | `spring.datasource.*` |
| `POSTGRES_*` | `docker-compose.yml` | the postgres container's own init |
| `JWT_SECRET`, `JWT_TTL_HOURS` | `application.yml` | `MoneyCompassProperties.Security` |
| `AI_PRIMARY`, `AI_FALLBACK`, `AI_TEMPERATURE`, `AI_MAX_TOKENS` | `application.yml` | `MoneyCompassProperties.Ai` |
| `OLLAMA_*` | `application.yml` | `spring.ai.ollama.*` |
| `OPENAI_*` | `application.yml` | `spring.ai.openai.*` |
| `AWS_*` | `application.yml` | `spring.ai.bedrock.aws.*` |
| `BEDROCK_MODEL` | `application.yml` | `spring.ai.bedrock.converse.chat.options.model` |
| `CORS_ALLOWED_ORIGINS` | `application.yml` | `MoneyCompassProperties.Cors` |
| `QUESTIONNAIRE_MAX_QUESTIONS` | `application.yml` | `MoneyCompassProperties.Questionnaire` |
| `API_BASE_URL` | Angular build (Phase 7) | `environment.ts` |

`spring.ai.*` prefixes verified against the Spring AI 2.0 reference
documentation: `spring.ai.bedrock.aws` configures the AWS connection,
`spring.ai.bedrock.converse.chat` configures the Converse chat model.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `JWT_SECRET must be at least 32 characters` at startup | secret is short or empty |
| `Validation failed for query ... ddl-auto: validate` | entity mappings and V1__init.sql disagree; fix the entity or add a migration |
| `Connection refused: localhost:5432` from inside the backend container | `DB_HOST_FOR_COMPOSE` should be `postgres`, not `localhost` |
| `Connection refused: ollama:11434` from your host | `OLLAMA_BASE_URL` should be `localhost` outside the compose network |
| Bedrock `AccessDeniedException` | one of the three conditions in section 7; check model access first |
| OpenAI 429 on every call | no credit on the account, not a rate limit |
| Browser blocks every API call | origin missing from `CORS_ALLOWED_ORIGINS` |
| `.env` value appears with a `#` and trailing text | trailing comment on the same line as a value |
