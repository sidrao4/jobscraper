CREATE TABLE companies (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT        NOT NULL,
    source          TEXT        NOT NULL CHECK (source IN ('GREENHOUSE', 'LEVER')),
    board_token     TEXT        NOT NULL,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    last_polled_at  TIMESTAMPTZ,
    last_poll_error TEXT,
    UNIQUE (source, board_token)
);

CREATE TABLE job_postings (
    id               BIGSERIAL PRIMARY KEY,
    company_id       BIGINT      NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    external_id      TEXT        NOT NULL,
    title            TEXT        NOT NULL,
    normalized_title TEXT        NOT NULL,
    location         TEXT,
    department       TEXT,
    url              TEXT        NOT NULL,
    description      TEXT        NOT NULL DEFAULT '',
    content_hash     TEXT        NOT NULL,
    posted_at        TIMESTAMPTZ,
    first_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at        TIMESTAMPTZ,
    -- Points at the canonical posting when this one is a repost/duplicate; NULL means canonical.
    canonical_id     BIGINT      REFERENCES job_postings (id) ON DELETE SET NULL,
    embedding        REAL[],
    embedding_hash   TEXT,
    UNIQUE (company_id, external_id)
);

CREATE INDEX idx_job_postings_group ON job_postings (company_id, normalized_title) WHERE closed_at IS NULL;
CREATE INDEX idx_job_postings_canonical ON job_postings (canonical_id);
CREATE INDEX idx_job_postings_open_canonical ON job_postings (id) WHERE closed_at IS NULL AND canonical_id IS NULL;

CREATE TABLE ingestion_runs (
    id                BIGSERIAL PRIMARY KEY,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    companies_polled  INT NOT NULL DEFAULT 0,
    companies_failed  INT NOT NULL DEFAULT 0,
    postings_seen     INT NOT NULL DEFAULT 0,
    postings_new      INT NOT NULL DEFAULT 0,
    postings_closed   INT NOT NULL DEFAULT 0,
    duplicates_found  INT NOT NULL DEFAULT 0,
    postings_embedded INT NOT NULL DEFAULT 0
);
