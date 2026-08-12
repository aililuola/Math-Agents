CREATE DOMAIN sha256_hex AS char(64)
  CHECK (VALUE ~ '^[0-9a-f]{64}$');

CREATE TABLE run (
  run_id text PRIMARY KEY,
  problem_hash sha256_hex NOT NULL,
  status text NOT NULL CHECK (status <> ''),
  current_stage text NOT NULL CHECK (current_stage <> ''),
  config_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  fencing_token bigint NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE problem_contract (
  run_id text PRIMARY KEY REFERENCES run(run_id) ON DELETE CASCADE,
  integrity_hash sha256_hex NOT NULL,
  original_text text NOT NULL,
  normalized_text text NOT NULL,
  problem_kind text NOT NULL,
  output_language text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE strategy (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  strategy_id text NOT NULL,
  mechanism_signature sha256_hex,
  status text NOT NULL,
  score double precision,
  payload jsonb NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, strategy_id)
);

CREATE TABLE route (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  route_id text NOT NULL,
  strategy_id text,
  status text NOT NULL,
  latest_checkpoint_id text,
  failure_count integer NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
  stagnation_rounds integer NOT NULL DEFAULT 0 CHECK (stagnation_rounds >= 0),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, route_id),
  FOREIGN KEY (run_id, strategy_id)
    REFERENCES strategy(run_id, strategy_id)
);

CREATE TABLE route_member (
  run_id text NOT NULL,
  route_id text NOT NULL,
  agent_id text NOT NULL,
  role text NOT NULL,
  assigned_round integer NOT NULL CHECK (assigned_round >= 0),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (run_id, route_id, agent_id, role),
  FOREIGN KEY (run_id, route_id)
    REFERENCES route(run_id, route_id) ON DELETE CASCADE
);

CREATE TABLE proof_attempt (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  attempt_id text NOT NULL,
  route_id text NOT NULL,
  path_id text NOT NULL,
  author_agent_id text NOT NULL,
  status text NOT NULL,
  request_artifact_hash sha256_hex,
  response_artifact_hash sha256_hex,
  payload jsonb NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, attempt_id),
  FOREIGN KEY (run_id, route_id)
    REFERENCES route(run_id, route_id)
);

CREATE TABLE proof_step (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  step_id text NOT NULL,
  checkpoint_id text,
  delta_id text,
  ordinal integer NOT NULL CHECK (ordinal >= 0),
  normalized_hash sha256_hex NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, step_id),
  UNIQUE (run_id, checkpoint_id, ordinal)
);

CREATE TABLE claim (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  claim_id text NOT NULL,
  content_hash sha256_hex NOT NULL,
  status text NOT NULL,
  memory_tier text NOT NULL,
  author_agent_id text NOT NULL,
  confidence double precision NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
  payload jsonb NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, claim_id),
  UNIQUE (run_id, content_hash)
);

CREATE TABLE claim_dependency (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  source_claim_id text NOT NULL,
  target_claim_id text NOT NULL,
  dependency_type text NOT NULL,
  status text NOT NULL DEFAULT 'active',
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, source_claim_id, target_claim_id, dependency_type),
  CHECK (source_claim_id <> target_claim_id),
  FOREIGN KEY (run_id, source_claim_id)
    REFERENCES claim(run_id, claim_id),
  FOREIGN KEY (run_id, target_claim_id)
    REFERENCES claim(run_id, claim_id)
);

CREATE TABLE message (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  message_id text NOT NULL,
  content_hash sha256_hex NOT NULL,
  source_agent_id text NOT NULL,
  source_route_id text NOT NULL,
  message_type text NOT NULL,
  priority integer NOT NULL DEFAULT 0,
  round_index integer NOT NULL CHECK (round_index >= 0),
  ttl_rounds integer NOT NULL CHECK (ttl_rounds >= 0),
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, message_id),
  UNIQUE (run_id, content_hash),
  FOREIGN KEY (run_id, source_route_id)
    REFERENCES route(run_id, route_id)
);

CREATE TABLE message_delivery (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  delivery_key text NOT NULL,
  message_id text NOT NULL,
  target_route_id text NOT NULL,
  target_agent_id text,
  state text NOT NULL,
  delivered_at timestamptz,
  prompt_consumed_at timestamptz,
  acknowledged_at timestamptz,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, delivery_key),
  FOREIGN KEY (run_id, message_id)
    REFERENCES message(run_id, message_id),
  FOREIGN KEY (run_id, target_route_id)
    REFERENCES route(run_id, route_id)
);

CREATE TABLE message_receipt (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  receipt_id text NOT NULL,
  delivery_key text NOT NULL,
  status text NOT NULL,
  semantic_hash sha256_hex,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  superseded_at timestamptz,
  PRIMARY KEY (run_id, receipt_id),
  FOREIGN KEY (run_id, delivery_key)
    REFERENCES message_delivery(run_id, delivery_key)
);

CREATE UNIQUE INDEX uq_message_receipt_current
  ON message_receipt(run_id, delivery_key)
  WHERE superseded_at IS NULL;

CREATE TABLE message_utility (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  utility_id text NOT NULL,
  delivery_key text NOT NULL,
  step_id text,
  obligation_id text,
  claimed_utility text,
  verified_utility text,
  verified_by text,
  status text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, utility_id),
  FOREIGN KEY (run_id, delivery_key)
    REFERENCES message_delivery(run_id, delivery_key)
);

CREATE TABLE memory_item (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  memory_id text NOT NULL,
  claim_id text,
  message_id text,
  memory_tier text NOT NULL,
  state text NOT NULL,
  content_hash sha256_hex NOT NULL,
  payload jsonb NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, memory_id),
  UNIQUE (run_id, content_hash)
);

CREATE TABLE memory_dependency (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  source_memory_id text NOT NULL,
  target_memory_id text NOT NULL,
  dependency_type text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (run_id, source_memory_id, target_memory_id, dependency_type),
  CHECK (source_memory_id <> target_memory_id),
  FOREIGN KEY (run_id, source_memory_id)
    REFERENCES memory_item(run_id, memory_id),
  FOREIGN KEY (run_id, target_memory_id)
    REFERENCES memory_item(run_id, memory_id)
);

CREATE TABLE memory_provenance (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  provenance_id text NOT NULL,
  memory_id text NOT NULL,
  source_artifact_hash sha256_hex,
  source_agent_id text,
  source_route_id text,
  verification_report_id text,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, provenance_id),
  FOREIGN KEY (run_id, memory_id)
    REFERENCES memory_item(run_id, memory_id)
);

CREATE TABLE memory_invalidation (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  invalidation_id text NOT NULL,
  memory_id text NOT NULL,
  counterexample_claim_id text,
  reason text NOT NULL,
  propagation_batch_id text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, invalidation_id),
  FOREIGN KEY (run_id, memory_id)
    REFERENCES memory_item(run_id, memory_id)
);

CREATE TABLE proof_obligation (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  obligation_id text NOT NULL,
  obligation_type text NOT NULL,
  domain text,
  status text NOT NULL,
  priority integer NOT NULL DEFAULT 0,
  debt double precision NOT NULL DEFAULT 0 CHECK (debt >= 0),
  owner_route_id text,
  payload jsonb NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, obligation_id)
);

CREATE TABLE proof_graph_edge (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  edge_id text NOT NULL,
  source_ref text NOT NULL,
  source_type text NOT NULL,
  target_ref text NOT NULL,
  target_type text NOT NULL,
  relation text NOT NULL,
  status text NOT NULL,
  provenance_ref text,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, edge_id),
  CHECK (source_ref <> target_ref OR source_type <> target_type),
  UNIQUE (run_id, source_ref, source_type, target_ref, target_type, relation)
);

CREATE TABLE proof_checkpoint (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  checkpoint_id text NOT NULL,
  path_id text NOT NULL,
  strategy_id text NOT NULL,
  parent_checkpoint_id text,
  segment_index integer NOT NULL CHECK (segment_index >= 0),
  content_hash sha256_hex NOT NULL,
  status text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, checkpoint_id),
  UNIQUE (run_id, path_id, segment_index, checkpoint_id),
  FOREIGN KEY (run_id, strategy_id)
    REFERENCES strategy(run_id, strategy_id)
);

CREATE TABLE working_checkpoint (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  working_checkpoint_id text NOT NULL,
  path_id text NOT NULL,
  strategy_id text NOT NULL,
  parent_verified_checkpoint_id text,
  segment_index integer NOT NULL CHECK (segment_index >= 0),
  status text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, working_checkpoint_id),
  FOREIGN KEY (run_id, strategy_id)
    REFERENCES strategy(run_id, strategy_id)
);

CREATE TABLE verification_report (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  report_id text NOT NULL,
  verification_stage text NOT NULL,
  verdict text NOT NULL,
  author_agent_id text NOT NULL,
  reviewer_agent_id text,
  target_ref text NOT NULL,
  confidence double precision CHECK (confidence >= 0 AND confidence <= 1),
  request_artifact_hash sha256_hex,
  response_artifact_hash sha256_hex,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, report_id)
);

CREATE TABLE referee_claim_ledger (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  claim_id text NOT NULL,
  reviewer_agent_id text NOT NULL,
  verdict text NOT NULL,
  review_version bigint NOT NULL CHECK (review_version >= 0),
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, claim_id, reviewer_agent_id, review_version)
);

CREATE TABLE meta_review (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  review_id text NOT NULL,
  round_index integer NOT NULL CHECK (round_index >= 0),
  action text NOT NULL,
  failure_level text,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, review_id)
);

CREATE TABLE proof_control_state (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  route_id text NOT NULL,
  semantic_sidecar jsonb NOT NULL DEFAULT '{}'::jsonb,
  control_state jsonb NOT NULL DEFAULT '{}'::jsonb,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, route_id)
);

CREATE TABLE control_action (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  control_action_id text NOT NULL,
  action_key text NOT NULL,
  action_kind text NOT NULL,
  status text NOT NULL,
  target_ref text,
  payload jsonb NOT NULL,
  applied_at timestamptz,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, control_action_id),
  UNIQUE (run_id, action_key)
);

CREATE TABLE inspiration_proposal (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  proposal_id text NOT NULL,
  mechanism_signature sha256_hex,
  author_agent_id text NOT NULL,
  trigger_kind text NOT NULL,
  novelty_score double precision,
  review_status text,
  status text NOT NULL,
  payload jsonb NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, proposal_id)
);

CREATE TABLE inspiration_outcome (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  outcome_id text NOT NULL,
  proposal_id text NOT NULL,
  route_id text,
  fact_ref text,
  citation_credit double precision,
  outcome text NOT NULL,
  reward double precision,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, outcome_id),
  FOREIGN KEY (run_id, proposal_id)
    REFERENCES inspiration_proposal(run_id, proposal_id)
);

CREATE TABLE experiment_spec (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  experiment_id text NOT NULL,
  method text NOT NULL,
  purpose text NOT NULL,
  limits_payload jsonb NOT NULL,
  status text NOT NULL,
  payload jsonb NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, experiment_id)
);

CREATE TABLE experiment_result (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  result_id text NOT NULL,
  experiment_id text NOT NULL,
  result_ref text,
  certificate_ref text,
  evidence_payload jsonb NOT NULL,
  decision text,
  artifact_hash sha256_hex,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, result_id),
  FOREIGN KEY (run_id, experiment_id)
    REFERENCES experiment_spec(run_id, experiment_id)
);

CREATE TABLE computation_cache (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  method text NOT NULL,
  canonical_identity_hash sha256_hex NOT NULL,
  tool_version text NOT NULL,
  result_artifact_hash sha256_hex NOT NULL,
  evidence_payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz,
  PRIMARY KEY (run_id, method, canonical_identity_hash, tool_version)
);

CREATE TABLE artifact (
  content_hash sha256_hex PRIMARY KEY,
  size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
  media_type text NOT NULL,
  storage_path text NOT NULL UNIQUE,
  provenance_source text NOT NULL,
  retention_policy text NOT NULL,
  encryption_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE run_artifact (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  content_hash sha256_hex NOT NULL REFERENCES artifact(content_hash),
  purpose text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, content_hash, purpose)
);

CREATE TABLE provider_call (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  call_id text NOT NULL,
  idempotency_key text NOT NULL,
  agent_id text NOT NULL,
  provider text NOT NULL,
  request_hash sha256_hex NOT NULL,
  state text NOT NULL,
  input_tokens bigint,
  output_tokens bigint,
  cost_amount numeric(20, 8),
  request_artifact_hash sha256_hex,
  response_artifact_hash sha256_hex,
  ambiguity_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, call_id),
  UNIQUE (run_id, idempotency_key)
);

CREATE TABLE usage_ledger (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  agent_id text NOT NULL,
  stage text NOT NULL,
  ledger_version bigint NOT NULL CHECK (ledger_version >= 0),
  reserved_tokens bigint NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0),
  actual_tokens bigint NOT NULL DEFAULT 0 CHECK (actual_tokens >= 0),
  reconciled_at timestamptz,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, agent_id, stage, ledger_version)
);

CREATE TABLE event_log (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  sequence bigint NOT NULL CHECK (sequence > 0),
  event_id text NOT NULL UNIQUE,
  aggregate_type text NOT NULL,
  aggregate_id text NOT NULL,
  event_type text NOT NULL,
  aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
  payload jsonb NOT NULL,
  event_hash sha256_hex NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, sequence)
);

CREATE TABLE outbox_event (
  event_id text PRIMARY KEY,
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  aggregate_type text NOT NULL,
  aggregate_id text NOT NULL,
  aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  claimed_by text,
  claimed_at timestamptz,
  published_at timestamptz,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE inbox_event (
  consumer_name text NOT NULL,
  event_id text NOT NULL,
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  processed_at timestamptz,
  result_payload jsonb,
  PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE run_lease (
  run_id text PRIMARY KEY REFERENCES run(run_id) ON DELETE CASCADE,
  owner_id text NOT NULL,
  fencing_token bigint NOT NULL CHECK (fencing_token > 0),
  expires_at timestamptz NOT NULL,
  heartbeat_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE legacy_import (
  import_id text PRIMARY KEY,
  source_manifest_hash sha256_hex NOT NULL UNIQUE,
  legacy_version text NOT NULL,
  target_run_id text NOT NULL REFERENCES run(run_id),
  status text NOT NULL,
  report_artifact_hash sha256_hex,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz
);

CREATE INDEX idx_strategy_run ON strategy(run_id);
CREATE INDEX idx_route_run ON route(run_id);
CREATE INDEX idx_route_member_run ON route_member(run_id);
CREATE INDEX idx_proof_attempt_run ON proof_attempt(run_id);
CREATE INDEX idx_proof_step_run ON proof_step(run_id);
CREATE INDEX idx_claim_run ON claim(run_id);
CREATE INDEX idx_claim_dependency_run ON claim_dependency(run_id);
CREATE INDEX idx_message_run ON message(run_id);
CREATE INDEX idx_message_delivery_run_state
  ON message_delivery(run_id, state);
CREATE INDEX idx_message_receipt_run ON message_receipt(run_id);
CREATE INDEX idx_message_utility_run ON message_utility(run_id);
CREATE INDEX idx_memory_item_run ON memory_item(run_id);
CREATE INDEX idx_memory_dependency_run ON memory_dependency(run_id);
CREATE INDEX idx_memory_provenance_run ON memory_provenance(run_id);
CREATE INDEX idx_memory_invalidation_run ON memory_invalidation(run_id);
CREATE INDEX idx_proof_obligation_run_status
  ON proof_obligation(run_id, status);
CREATE INDEX idx_proof_graph_edge_run ON proof_graph_edge(run_id);
CREATE INDEX idx_proof_checkpoint_run_path
  ON proof_checkpoint(run_id, path_id, segment_index);
CREATE INDEX idx_working_checkpoint_run_path
  ON working_checkpoint(run_id, path_id, segment_index);
CREATE INDEX idx_verification_report_run ON verification_report(run_id);
CREATE INDEX idx_referee_claim_ledger_run ON referee_claim_ledger(run_id);
CREATE INDEX idx_meta_review_run ON meta_review(run_id);
CREATE INDEX idx_proof_control_state_run ON proof_control_state(run_id);
CREATE INDEX idx_control_action_run ON control_action(run_id);
CREATE INDEX idx_inspiration_proposal_run ON inspiration_proposal(run_id);
CREATE INDEX idx_inspiration_outcome_run ON inspiration_outcome(run_id);
CREATE INDEX idx_experiment_spec_run ON experiment_spec(run_id);
CREATE INDEX idx_experiment_result_run ON experiment_result(run_id);
CREATE INDEX idx_computation_cache_run ON computation_cache(run_id);
CREATE INDEX idx_run_artifact_run ON run_artifact(run_id);
CREATE INDEX idx_provider_call_run_state ON provider_call(run_id, state);
CREATE INDEX idx_usage_ledger_run ON usage_ledger(run_id);
CREATE INDEX idx_event_log_run_type ON event_log(run_id, event_type);
CREATE INDEX idx_outbox_available
  ON outbox_event(available_at, event_id)
  WHERE published_at IS NULL;
CREATE INDEX idx_outbox_run ON outbox_event(run_id);
CREATE INDEX idx_inbox_run ON inbox_event(run_id);
CREATE INDEX idx_run_lease_expiry ON run_lease(expires_at);
CREATE INDEX idx_legacy_import_run ON legacy_import(target_run_id);

CREATE FUNCTION reject_row_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER event_log_append_only
BEFORE UPDATE OR DELETE ON event_log
FOR EACH ROW EXECUTE FUNCTION reject_row_mutation();

CREATE TRIGGER verification_report_append_only
BEFORE UPDATE OR DELETE ON verification_report
FOR EACH ROW EXECUTE FUNCTION reject_row_mutation();

CREATE TRIGGER memory_invalidation_append_only
BEFORE UPDATE OR DELETE ON memory_invalidation
FOR EACH ROW EXECUTE FUNCTION reject_row_mutation();

CREATE FUNCTION protect_provider_request()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.request_hash IS DISTINCT FROM OLD.request_hash
     OR NEW.request_artifact_hash IS DISTINCT FROM OLD.request_artifact_hash
     OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
     OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
     OR NEW.provider IS DISTINCT FROM OLD.provider THEN
    RAISE EXCEPTION 'provider request identity is immutable'
      USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER provider_request_immutable
BEFORE UPDATE ON provider_call
FOR EACH ROW EXECUTE FUNCTION protect_provider_request();
