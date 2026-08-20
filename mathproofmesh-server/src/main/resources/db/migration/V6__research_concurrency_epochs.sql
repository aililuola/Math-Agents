CREATE TABLE research_epoch (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  epoch_id text NOT NULL,
  snapshot_hash sha256_hex NOT NULL,
  status text NOT NULL CHECK (status <> ''),
  merge_plan_hash text NOT NULL DEFAULT '',
  payload jsonb NOT NULL,
  version bigint NOT NULL CHECK (version >= 0),
  fencing_token bigint NOT NULL CHECK (fencing_token >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, epoch_id)
);

CREATE TABLE research_work_item (
  run_id text NOT NULL,
  epoch_id text NOT NULL,
  task_id text NOT NULL,
  snapshot_hash sha256_hex NOT NULL,
  task_status text NOT NULL CHECK (task_status <> ''),
  agent_id text NOT NULL DEFAULT '',
  provider_request_id text NOT NULL DEFAULT '',
  result_ref text NOT NULL DEFAULT '',
  result_hash text NOT NULL DEFAULT '',
  payload jsonb NOT NULL,
  version bigint NOT NULL CHECK (version >= 0),
  fencing_token bigint NOT NULL CHECK (fencing_token >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, task_id),
  FOREIGN KEY (run_id, epoch_id)
    REFERENCES research_epoch(run_id, epoch_id)
    ON DELETE CASCADE
);

CREATE TABLE agent_lease (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  lease_id text NOT NULL,
  epoch_id text NOT NULL,
  task_id text NOT NULL,
  agent_id text NOT NULL CHECK (agent_id <> ''),
  lease_class text NOT NULL CHECK (lease_class <> ''),
  lease_status text NOT NULL CHECK (lease_status <> ''),
  acquired_at timestamptz NOT NULL,
  released_at timestamptz,
  version bigint NOT NULL CHECK (version >= 0),
  fencing_token bigint NOT NULL CHECK (fencing_token >= 0),
  PRIMARY KEY (run_id, lease_id),
  FOREIGN KEY (run_id, task_id)
    REFERENCES research_work_item(run_id, task_id)
    ON DELETE CASCADE
);

CREATE TABLE concurrency_telemetry_event (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  sequence bigint NOT NULL CHECK (sequence >= 0),
  epoch_id text NOT NULL DEFAULT '',
  task_id text NOT NULL DEFAULT '',
  agent_id text NOT NULL DEFAULT '',
  event_type text NOT NULL CHECK (event_type <> ''),
  monotonic_nanos bigint NOT NULL CHECK (monotonic_nanos >= 0),
  ready_work_count integer NOT NULL CHECK (ready_work_count >= 0),
  fencing_token bigint NOT NULL CHECK (fencing_token >= 0),
  PRIMARY KEY (run_id, sequence)
);

CREATE INDEX idx_research_epoch_run_status
  ON research_epoch(run_id, status);

CREATE INDEX idx_research_work_item_epoch_status
  ON research_work_item(run_id, epoch_id, task_status);

CREATE INDEX idx_agent_lease_run_status
  ON agent_lease(run_id, lease_status);

CREATE INDEX idx_concurrency_telemetry_run_epoch
  ON concurrency_telemetry_event(run_id, epoch_id, sequence);
