ALTER TABLE message
  ADD COLUMN dedupe_key sha256_hex;

UPDATE message
SET dedupe_key = content_hash
WHERE dedupe_key IS NULL;

ALTER TABLE message
  ALTER COLUMN dedupe_key SET NOT NULL;

CREATE UNIQUE INDEX uq_message_dedupe_key
  ON message(run_id, dedupe_key);

ALTER TABLE message_delivery
  ADD COLUMN priority_name text NOT NULL DEFAULT 'low',
  ADD COLUMN delivered_round integer NOT NULL DEFAULT 0
    CHECK (delivered_round >= 0),
  ADD COLUMN processing_opportunities integer NOT NULL DEFAULT 0
    CHECK (processing_opportunities >= 0),
  ADD COLUMN provider_request_id text,
  ADD COLUMN receipt_token text NOT NULL DEFAULT '',
  ADD COLUMN actually_used boolean NOT NULL DEFAULT false;

CREATE TABLE provider_prompt_request (
  run_id text NOT NULL REFERENCES run(run_id) ON DELETE CASCADE,
  request_id text NOT NULL,
  target_route_id text NOT NULL,
  state text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (run_id, request_id),
  FOREIGN KEY (run_id, target_route_id)
    REFERENCES route(run_id, route_id)
);

CREATE INDEX ix_message_delivery_prompt_queue
  ON message_delivery(run_id, target_route_id, state, priority_name, delivered_round);
