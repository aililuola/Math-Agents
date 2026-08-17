CREATE TABLE run_state_snapshot (
  run_id text PRIMARY KEY REFERENCES run(run_id) ON DELETE CASCADE,
  authority_sequence bigint NOT NULL CHECK (authority_sequence >= 0),
  execution_attempt_id text NOT NULL CHECK (execution_attempt_id <> ''),
  execution_status text NOT NULL CHECK (execution_status <> ''),
  math_status text NOT NULL CHECK (math_status <> ''),
  usage_status text NOT NULL CHECK (usage_status <> ''),
  campaign_status text NOT NULL CHECK (campaign_status <> ''),
  report_status text NOT NULL CHECK (report_status <> ''),
  authority_hash sha256_hex NOT NULL,
  state_hash sha256_hex NOT NULL,
  state_payload jsonb NOT NULL,
  version bigint NOT NULL CHECK (version >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE run_state_transition (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  transition_id text NOT NULL,
  sequence bigint NOT NULL CHECK (sequence >= 0),
  from_state_hash text NOT NULL,
  to_state_hash sha256_hex NOT NULL,
  trigger text NOT NULL CHECK (trigger <> ''),
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (run_id, transition_id),
  UNIQUE (run_id, sequence)
);

CREATE INDEX idx_run_state_transition_run_sequence
  ON run_state_transition(run_id, sequence);
