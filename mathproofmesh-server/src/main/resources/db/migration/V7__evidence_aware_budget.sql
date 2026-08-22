CREATE TABLE pricing_snapshot (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  pricing_hash sha256_hex NOT NULL,
  provider text NOT NULL CHECK (provider <> ''),
  model text NOT NULL CHECK (model <> ''),
  input_per_million numeric(30, 12) NOT NULL CHECK (input_per_million >= 0),
  output_per_million numeric(30, 12) NOT NULL CHECK (output_per_million >= 0),
  billing_mode text NOT NULL
    CHECK (billing_mode IN ('BILLED', 'BILLING_EXEMPT', 'UNKNOWN')),
  config_hash sha256_hex NOT NULL,
  content_hash sha256_hex NOT NULL,
  version bigint NOT NULL CHECK (version >= 0),
  fencing_token bigint NOT NULL CHECK (fencing_token >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, pricing_hash)
);

CREATE TABLE budget_decision (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  epoch_id text NOT NULL,
  decision_id text NOT NULL,
  budget_state_hash sha256_hex NOT NULL,
  selected_action text NOT NULL CHECK (selected_action <> ''),
  status text NOT NULL CHECK (status <> ''),
  calls bigint NOT NULL CHECK (calls >= 0),
  estimated_input_tokens bigint NOT NULL CHECK (estimated_input_tokens >= 0),
  max_output_tokens bigint NOT NULL CHECK (max_output_tokens >= 0),
  max_total_tokens bigint NOT NULL CHECK (
    max_total_tokens >= 0
    AND max_total_tokens = estimated_input_tokens + max_output_tokens
  ),
  max_cost_usd numeric(30, 12) NOT NULL CHECK (max_cost_usd >= 0),
  bucket text NOT NULL CHECK (bucket <> ''),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  content_hash sha256_hex NOT NULL,
  version bigint NOT NULL CHECK (version >= 0),
  fencing_token bigint NOT NULL CHECK (fencing_token >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, decision_id),
  UNIQUE (run_id, budget_state_hash)
);

CREATE TABLE budget_envelope (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  epoch_id text NOT NULL,
  decision_id text NOT NULL,
  envelope_id text NOT NULL,
  work_item_id text NOT NULL CHECK (work_item_id <> ''),
  status text NOT NULL CHECK (status <> ''),
  calls bigint NOT NULL CHECK (calls >= 0),
  estimated_input_tokens bigint NOT NULL CHECK (estimated_input_tokens >= 0),
  max_output_tokens bigint NOT NULL CHECK (max_output_tokens >= 0),
  max_total_tokens bigint NOT NULL CHECK (
    max_total_tokens >= 0
    AND max_total_tokens = estimated_input_tokens + max_output_tokens
  ),
  max_cost_usd numeric(30, 12) NOT NULL CHECK (max_cost_usd >= 0),
  bucket text NOT NULL CHECK (bucket <> ''),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  content_hash sha256_hex NOT NULL,
  version bigint NOT NULL CHECK (version >= 0),
  fencing_token bigint NOT NULL CHECK (fencing_token >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, envelope_id),
  FOREIGN KEY (run_id, decision_id)
    REFERENCES budget_decision(run_id, decision_id)
    ON DELETE CASCADE
);

CREATE TABLE budget_reservation (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  epoch_id text NOT NULL,
  envelope_id text NOT NULL,
  reservation_id text NOT NULL,
  provider_call_id text NOT NULL,
  idempotency_key text NOT NULL CHECK (idempotency_key <> ''),
  status text NOT NULL CHECK (status <> ''),
  calls bigint NOT NULL CHECK (calls >= 0),
  estimated_input_tokens bigint NOT NULL CHECK (estimated_input_tokens >= 0),
  max_output_tokens bigint NOT NULL CHECK (max_output_tokens >= 0),
  max_total_tokens bigint NOT NULL CHECK (
    max_total_tokens >= 0
    AND max_total_tokens = estimated_input_tokens + max_output_tokens
  ),
  max_cost_usd numeric(30, 12) NOT NULL CHECK (max_cost_usd >= 0),
  pricing_hash sha256_hex NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  content_hash sha256_hex NOT NULL,
  version bigint NOT NULL CHECK (version >= 0),
  fencing_token bigint NOT NULL CHECK (fencing_token >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, reservation_id),
  UNIQUE (run_id, idempotency_key, provider_call_id),
  FOREIGN KEY (run_id, envelope_id)
    REFERENCES budget_envelope(run_id, envelope_id)
    ON DELETE CASCADE,
  FOREIGN KEY (run_id, provider_call_id)
    REFERENCES provider_call(run_id, call_id)
    ON DELETE RESTRICT,
  FOREIGN KEY (run_id, pricing_hash)
    REFERENCES pricing_snapshot(run_id, pricing_hash)
    ON DELETE RESTRICT
);

CREATE TABLE budget_usage_event (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  epoch_id text NOT NULL,
  usage_event_id text NOT NULL,
  envelope_id text NOT NULL,
  reservation_id text NOT NULL,
  provider_call_id text NOT NULL,
  status text NOT NULL CHECK (status <> ''),
  actual_calls bigint NOT NULL CHECK (actual_calls >= 0),
  actual_input_tokens bigint NOT NULL CHECK (actual_input_tokens >= 0),
  actual_output_tokens bigint NOT NULL CHECK (actual_output_tokens >= 0),
  actual_total_tokens bigint NOT NULL CHECK (
    actual_total_tokens >= 0
    AND actual_total_tokens = actual_input_tokens + actual_output_tokens
  ),
  actual_cost_usd numeric(30, 12) NOT NULL CHECK (actual_cost_usd >= 0),
  bucket text NOT NULL CHECK (bucket <> ''),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  content_hash sha256_hex NOT NULL,
  version bigint NOT NULL CHECK (version >= 0),
  fencing_token bigint NOT NULL CHECK (fencing_token >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, usage_event_id),
  UNIQUE (run_id, reservation_id),
  FOREIGN KEY (run_id, reservation_id)
    REFERENCES budget_reservation(run_id, reservation_id)
    ON DELETE RESTRICT,
  FOREIGN KEY (run_id, provider_call_id)
    REFERENCES provider_call(run_id, call_id)
    ON DELETE RESTRICT
);

CREATE TABLE budget_zero_gain_state (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  epoch_id text NOT NULL,
  target_mechanism_key text NOT NULL,
  consecutive_zero_gain integer NOT NULL CHECK (consecutive_zero_gain >= 0),
  global_zero_gain_rounds integer NOT NULL CHECK (global_zero_gain_rounds >= 0),
  exhausted boolean NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  content_hash sha256_hex NOT NULL,
  version bigint NOT NULL CHECK (version >= 0),
  fencing_token bigint NOT NULL CHECK (fencing_token >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, target_mechanism_key)
);

CREATE OR REPLACE FUNCTION reject_stale_budget_writer()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.fencing_token < OLD.fencing_token
     OR NEW.version <= OLD.version THEN
    RAISE EXCEPTION 'stale budget writer'
      USING ERRCODE = '40001';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER budget_decision_stale_writer
BEFORE UPDATE ON budget_decision
FOR EACH ROW EXECUTE FUNCTION reject_stale_budget_writer();

CREATE TRIGGER budget_envelope_stale_writer
BEFORE UPDATE ON budget_envelope
FOR EACH ROW EXECUTE FUNCTION reject_stale_budget_writer();

CREATE TRIGGER budget_reservation_stale_writer
BEFORE UPDATE ON budget_reservation
FOR EACH ROW EXECUTE FUNCTION reject_stale_budget_writer();

CREATE TRIGGER budget_zero_gain_stale_writer
BEFORE UPDATE ON budget_zero_gain_state
FOR EACH ROW EXECUTE FUNCTION reject_stale_budget_writer();

CREATE INDEX idx_budget_decision_run_epoch
  ON budget_decision(run_id, epoch_id);

CREATE INDEX idx_budget_envelope_run_status
  ON budget_envelope(run_id, status);

CREATE INDEX idx_budget_reservation_run_status
  ON budget_reservation(run_id, status);

CREATE INDEX idx_budget_usage_event_run_epoch
  ON budget_usage_event(run_id, epoch_id);
