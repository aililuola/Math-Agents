ALTER TABLE memory_item
  ADD COLUMN invalidated_reason text,
  ADD COLUMN propagation_batch_id text;

ALTER TABLE proof_obligation
  ADD COLUMN needs_reverify boolean NOT NULL DEFAULT false,
  ADD COLUMN propagation_batch_id text;

CREATE TABLE proof_graph_state (
  run_id text PRIMARY KEY REFERENCES run(run_id) ON DELETE CASCADE,
  problem_hash sha256_hex NOT NULL,
  frozen boolean NOT NULL DEFAULT false,
  graph_version bigint NOT NULL DEFAULT 0 CHECK (graph_version >= 0),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE UNIQUE INDEX uq_memory_invalidation_batch_item
  ON memory_invalidation(run_id, propagation_batch_id, memory_id);

CREATE INDEX ix_memory_dependency_reverse
  ON memory_dependency(run_id, target_memory_id, source_memory_id);

CREATE INDEX ix_proof_graph_edge_reverse
  ON proof_graph_edge(run_id, target_ref, source_ref)
  WHERE relation = 'depends_on' AND status = 'active';

CREATE INDEX ix_proof_obligation_reverify
  ON proof_obligation(run_id, needs_reverify)
  WHERE needs_reverify;
