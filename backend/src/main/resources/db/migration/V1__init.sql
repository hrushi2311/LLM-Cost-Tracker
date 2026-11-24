-- V1: initial schema, copied verbatim from SPEC.md (Data model).
-- Never edit this file once applied; every later change is a new migration.

CREATE TABLE project (
  id          UUID PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE,
  key_hash    TEXT NOT NULL UNIQUE,      -- SHA-256 of the full project key
  key_last4   TEXT NOT NULL,             -- shown masked in the UI
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent (
  id            UUID PRIMARY KEY,
  project_id    UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,           -- slug, never changes
  display_name  TEXT,
  source        TEXT NOT NULL CHECK (source IN ('path','header','fingerprint','unattributed')),
  fingerprint   TEXT,                    -- SHA-256 hex, fingerprint agents only
  first_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, name)
);

CREATE TABLE agent_alias (
  project_id  UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  alias       TEXT NOT NULL,             -- a slug or 'fp:<hash>'
  agent_id    UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
  PRIMARY KEY (project_id, alias)
);

CREATE TABLE model_price (
  provider               TEXT NOT NULL,
  model                  TEXT NOT NULL,  -- exact name or prefix (see Cost calculation)
  effective_from         DATE NOT NULL,
  input_per_mtok         NUMERIC(12,6) NOT NULL,  -- USD per 1M tokens
  output_per_mtok        NUMERIC(12,6) NOT NULL,
  cache_read_per_mtok    NUMERIC(12,6),
  cache_write_per_mtok   NUMERIC(12,6),
  source                 TEXT NOT NULL DEFAULT 'seed' CHECK (source IN ('seed','user')),
  PRIMARY KEY (provider, model, effective_from)
);

CREATE TABLE usage_event (
  id                  BIGSERIAL PRIMARY KEY,
  project_id          UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  agent_id            UUID REFERENCES agent(id) ON DELETE SET NULL,
  run_id              TEXT,
  provider            TEXT NOT NULL,
  endpoint            TEXT NOT NULL,     -- 'chat.completions' | 'responses' | 'messages'
  model               TEXT,
  input_tokens        INTEGER NOT NULL DEFAULT 0,  -- uncached input
  output_tokens       INTEGER NOT NULL DEFAULT 0,
  cache_read_tokens   INTEGER NOT NULL DEFAULT 0,
  cache_write_tokens  INTEGER NOT NULL DEFAULT 0,
  reasoning_tokens    INTEGER NOT NULL DEFAULT 0,  -- display only
  cost_usd            NUMERIC(14,8),     -- NULL when unpriced
  streamed            BOOLEAN NOT NULL DEFAULT false,
  incomplete          BOOLEAN NOT NULL DEFAULT false,
  status_code         INTEGER NOT NULL,
  latency_ms          INTEGER NOT NULL,
  tags                JSONB,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ue_project_time ON usage_event (project_id, created_at);
CREATE INDEX ue_agent_time   ON usage_event (project_id, agent_id, created_at);
CREATE INDEX ue_run          ON usage_event (project_id, run_id, created_at) WHERE run_id IS NOT NULL;
CREATE INDEX ue_unpriced     ON usage_event (provider, model) WHERE cost_usd IS NULL;

CREATE TABLE budget (
  id          UUID PRIMARY KEY,
  project_id  UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  agent_id    UUID REFERENCES agent(id) ON DELETE CASCADE,  -- NULL = whole project
  period      TEXT NOT NULL CHECK (period IN ('day','month')),
  limit_usd   NUMERIC(12,2) NOT NULL CHECK (limit_usd > 0),
  alert_pct   INTEGER NOT NULL DEFAULT 80 CHECK (alert_pct BETWEEN 1 AND 100),
  enforce     BOOLEAN NOT NULL DEFAULT false,
  UNIQUE NULLS NOT DISTINCT (project_id, agent_id, period)
);

CREATE TABLE alert_event (
  id            BIGSERIAL PRIMARY KEY,
  budget_id     UUID NOT NULL REFERENCES budget(id) ON DELETE CASCADE,
  period_start  DATE NOT NULL,
  kind          TEXT NOT NULL CHECK (kind IN ('threshold','exceeded')),
  spent_usd     NUMERIC(12,2) NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (budget_id, period_start, kind)
);
