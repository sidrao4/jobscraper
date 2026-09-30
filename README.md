# Job Aggregator & Resume Matching

A Spring Boot service that polls 58 public company job boards (Greenhouse and Lever), normalizes postings into PostgreSQL, collapses reposts and duplicates, and ranks open postings against a resume using Gemini embeddings and cosine similarity.

**Stack:** Java 21 · Spring Boot 4.1 · PostgreSQL 17 · Flyway · Docker · Gemini `gemini-embedding-001`

## What it does

```
 Greenhouse API ─┐                         ┌─ title normalization ─┐
                 ├─ scheduled REST poll ──►│                       ├─► PostgreSQL ─► dedup ─► Gemini embeddings ─► /api/match
 Lever API ──────┘   (virtual threads)     └─ HTML → text ─────────┘   (upsert,      (canonical     (canonical postings    (cosine
                                                                        close stale)  links)         only, change-aware)    ranking)
```

1. **Ingestion** (`ingest/`, `source/`). A cron schedule (every 6h by default, plus once at startup) polls every enabled board concurrently on virtual threads, with a semaphore to limit load on the APIs. Each board's JSON is mapped to one `NormalizedJob` shape: Greenhouse's entity-escaped HTML and Lever's split description sections are both converted to plain text. Postings are upserted by `(company, external_id)`. Postings that disappear from a board are marked closed. A board that fails to respond leaves its postings open.
2. **Title normalization** (`normalize/TitleNormalizer`). Folds case and accents, expands abbreviations (`Sr.` → senior, `SWE` → software engineer, `II` → 2), and removes requisition IDs. It also drops title segments that only repeat a location or work arrangement (`- Remote (US)`, `[London]`, `, Toronto` when the posting is in Toronto). Team names like `(Card Acquisition)` are kept, and so are seniority levels: `Engineer II` ≠ `Engineer III`.
3. **Deduplication** (`dedup/`). Postings are grouped by company and normalized title. Within a group, a posting whose description has Jaccard similarity ≥ 0.7 (over 4-word shingles) with an earlier posting becomes that posting's duplicate, and the earliest posting stays canonical. Before comparing, the service strips **company boilerplate**: any description line the company repeats across ≥ 3 different roles, such as benefits, EEO text or regional pay ranges. That way the score reflects the role itself, not shared text. Without this step, the same role posted in Poland and Spain scored only 0.60 because of the local pay and benefits sections.
4. **Embeddings** (`embedding/`). Only canonical, open postings are embedded (title, company, location, team and description) through Gemini's `batchEmbedContents` (768 dimensions, `RETRIEVAL_DOCUMENT`), 100 per request, with backoff on 429 and 5xx responses. Vectors are stored in a `REAL[]` column along with the hash of the content they were computed from, so a posting is re-embedded only when its text changes.
5. **Matching** (`match/`). The resume (pasted text or an uploaded PDF, parsed with PDFBox) is embedded as `RETRIEVAL_QUERY`. Every embedded posting is scored by cosine similarity, and the top N come back with an optional location filter.

## Results on live data (2026-09-30)

| | |
|---|---|
| Boards polled | 58 (43 Greenhouse, 15 Lever), 0 failures |
| Open postings ingested | 12,348, fetched and normalized in about 7 seconds |
| Duplicates collapsed | 2,121 (**17.2%** of listings) |
| Postings left to rank | 10,227 canonical postings |

Most duplicates are the same role posted separately for different cities or countries (for example Veeva *Clinical Compliance Operations Specialist* in London and Dublin, or Databricks *Director, Lakebase Sales Specialists* in Melbourne and Sydney). To pick the thresholds I swept shingle sizes 3–5 and thresholds 0.6–0.8 on this data. The duplicate rate stays between 16.4% and 17.6%, and sampled merges near the cutoff were true duplicates at every setting. The rate is therefore limited by title grouping, not by the similarity threshold.

## Running it

### Gemini API key

Copy `.env.example` to `.env` and paste your key after `GEMINI_API_KEY=`. Get a key at https://aistudio.google.com/apikey. The `.env` file is gitignored, and both run modes below read it. Without a key, ingestion and dedup still run, but matching returns 503.

### With Docker

```bash
docker compose up --build
```

Open http://localhost:8080.

### Without Docker

Tests and local development use a real embedded PostgreSQL (zonky), so Docker isn't required. Data persists in `target/dev-postgres`.

```bash
./mvnw spring-boot:test-run -Dspring-boot.test-run.main-class=com.jobaggregator.LocalDevApplication
```

### Tests

```bash
./mvnw test
```

The suite covers the title normalizer, shingle similarity, Greenhouse, Lever and Gemini client parsing (with `MockRestServiceServer`), and end-to-end ingestion against PostgreSQL. The end-to-end test checks upsert, dedup, boilerplate handling, embedding only changed postings, closing stale postings, surviving a failed poll, and resume ranking through the REST API.

## API

| Method | Path | |
|---|---|---|
| `POST` | `/api/match` | JSON `{"resumeText": "...", "limit": 25, "location": "New York"}`, or multipart `resume=@resume.pdf` |
| `GET` | `/api/jobs?q=&location=&companyId=&includeDuplicates=false&limit=50&offset=0` | Search open postings |
| `GET` | `/api/jobs/{id}` | A posting and its duplicates |
| `GET` | `/api/stats` | Counts, duplicate rate, recent ingestion runs |
| `POST` | `/api/ingest` | Start an ingestion run now |
| `GET` / `POST` | `/api/companies` | List boards, or add one: `{"name": "Acme", "source": "GREENHOUSE", "boardToken": "acme"}` |

```bash
curl -X POST localhost:8080/api/match -F resume=@resume.pdf -F limit=10
```

## Configuration

| Env var | Default | |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/jobs`, `jobs`, `jobs` | |
| `GEMINI_API_KEY` | — | Enables embeddings and matching |
| `EMBED_MAX_PER_RUN` | `0` (unlimited) | Caps embeddings per run to fit free-tier quotas |
| `INGEST_CRON` | `0 0 */6 * * *` | |
| `INGEST_ON_STARTUP` | `true` | |

Dedup settings are in `application.yml` under `app.dedup`.

## Schema

Flyway migrations are in `src/main/resources/db/migration`:

- `V1__init.sql` creates `companies`, `job_postings` (with a self-referencing `canonical_id`, `embedding REAL[]` and `embedding_hash`) and `ingestion_runs`.
- `V2__seed_companies.sql` seeds the 58 boards, each verified reachable.
