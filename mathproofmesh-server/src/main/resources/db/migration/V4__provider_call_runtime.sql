ALTER TABLE provider_call
  ADD COLUMN model text NOT NULL DEFAULT 'unknown',
  ADD COLUMN stage text NOT NULL DEFAULT 'unknown',
  ADD COLUMN request_id text,
  ADD COLUMN latency_ms numeric(20, 3) NOT NULL DEFAULT 0,
  ADD COLUMN retry_count integer NOT NULL DEFAULT 0,
  ADD COLUMN possible_duplicate_cost numeric(20, 8) NOT NULL DEFAULT 0,
  ADD COLUMN applied_at timestamptz,
  ADD COLUMN dispatched_at timestamptz,
  ADD COLUMN completed_at timestamptz;

ALTER TABLE provider_call
  ADD CONSTRAINT provider_call_state_check
    CHECK (
      state IN (
        'planned',
        'dispatched',
        'streaming',
        'succeeded',
        'failed',
        'ambiguous',
        'cancelled'
      )
    ),
  ADD CONSTRAINT provider_call_input_tokens_check
    CHECK (input_tokens IS NULL OR input_tokens >= 0),
  ADD CONSTRAINT provider_call_output_tokens_check
    CHECK (output_tokens IS NULL OR output_tokens >= 0),
  ADD CONSTRAINT provider_call_cost_check
    CHECK (cost_amount IS NULL OR cost_amount >= 0),
  ADD CONSTRAINT provider_call_latency_check
    CHECK (latency_ms >= 0),
  ADD CONSTRAINT provider_call_retry_count_check
    CHECK (retry_count >= 0),
  ADD CONSTRAINT provider_call_possible_duplicate_cost_check
    CHECK (possible_duplicate_cost >= 0);

CREATE TABLE provider_call_application (
  run_id text NOT NULL,
  application_key text NOT NULL,
  call_id text NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, application_key),
  UNIQUE (run_id, call_id),
  FOREIGN KEY (run_id, call_id)
    REFERENCES provider_call(run_id, call_id)
    ON DELETE CASCADE
);

CREATE TABLE provider_circuit_state (
  provider_scope text PRIMARY KEY,
  failures_payload jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(failures_payload) = 'array'),
  open_until timestamptz,
  version bigint NOT NULL CHECK (version >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_provider_call_request_id
  ON provider_call(request_id)
  WHERE request_id IS NOT NULL;

CREATE INDEX idx_provider_call_application_call
  ON provider_call_application(run_id, call_id);

CREATE OR REPLACE FUNCTION protect_provider_request()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.request_hash IS DISTINCT FROM OLD.request_hash
     OR NEW.request_artifact_hash IS DISTINCT FROM OLD.request_artifact_hash
     OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
     OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
     OR NEW.provider IS DISTINCT FROM OLD.provider
     OR NEW.model IS DISTINCT FROM OLD.model
     OR NEW.stage IS DISTINCT FROM OLD.stage THEN
    RAISE EXCEPTION 'provider request identity is immutable'
      USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END;
$$;
