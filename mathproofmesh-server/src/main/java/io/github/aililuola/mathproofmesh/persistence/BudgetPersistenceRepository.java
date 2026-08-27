package io.github.aililuola.mathproofmesh.persistence;

import io.github.aililuola.mathproofmesh.orchestration.BudgetResourceVector;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional PostgreSQL admission boundary for durable action envelopes. */
public final class BudgetPersistenceRepository {
  private static final String LOCK_RUN_SQL =
      """
      SELECT fencing_token
      FROM run
      WHERE run_id = :runId
      FOR UPDATE
      """;
  private static final String SELECT_ENVELOPE_HASH_SQL =
      """
      SELECT content_hash
      FROM budget_envelope
      WHERE run_id = :runId AND envelope_id = :envelopeId
      """;
  private static final String INSERT_ENVELOPE_SQL =
      """
      INSERT INTO budget_envelope (
        run_id, epoch_id, decision_id, envelope_id, work_item_id, status,
        calls, estimated_input_tokens, max_output_tokens, max_total_tokens,
        max_cost_usd, bucket, payload, content_hash, version, fencing_token
      ) VALUES (
        :runId, :epochId, :decisionId, :envelopeId, :workItemId, 'RESERVED',
        :calls, :inputTokens, :outputTokens, :totalTokens,
        :cost, :bucket, '{}'::jsonb, :contentHash, :version, :fencingToken
      )
      """;
  private static final String ACTIVE_EXPOSURE_SQL =
      """
      SELECT
        COALESCE(SUM(calls), 0) AS calls,
        COALESCE(SUM(estimated_input_tokens), 0) AS input_tokens,
        COALESCE(SUM(max_output_tokens), 0) AS output_tokens,
        COALESCE(SUM(max_total_tokens), 0) AS total_tokens,
        COALESCE(SUM(max_cost_usd), 0) AS cost
      FROM budget_envelope
      WHERE run_id = :runId
        AND status IN ('RESERVED', 'ACTIVE', 'QUARANTINED_UNCERTAIN')
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public BudgetPersistenceRepository(JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  /**
   * Serializes reservation admission on the run row. A replay with the same content hash is a
   * no-op; a conflicting identity or an over-cap request fails closed.
   */
  public boolean reserveEnvelope(
      String runId,
      String epochId,
      String decisionId,
      String envelopeId,
      String workItemId,
      String bucket,
      BudgetResourceVector requested,
      BudgetResourceVector configuredLimit,
      String contentHash,
      long version,
      long fencingToken) {
    Objects.requireNonNull(requested, "requested");
    Objects.requireNonNull(configuredLimit, "configuredLimit");
    Boolean inserted =
        transactions.execute(
            ignored -> {
              Long authoritativeFence =
                  jdbc.sql(LOCK_RUN_SQL)
                      .param("runId", required(runId, "runId"))
                      .query(Long.class)
                      .optional()
                      .orElseThrow(() -> new IllegalStateException("budget run does not exist"));
              if (authoritativeFence != fencingToken) {
                throw new IllegalStateException("STALE_BUDGET_WRITER");
              }
              String priorHash =
                  jdbc.sql(SELECT_ENVELOPE_HASH_SQL)
                      .param("runId", runId)
                      .param("envelopeId", required(envelopeId, "envelopeId"))
                      .query(String.class)
                      .optional()
                      .orElse(null);
              if (priorHash != null) {
                if (!sameHash(priorHash, required(contentHash, "contentHash"))) {
                  throw new IllegalStateException("CONFLICTING_BUDGET_ENVELOPE_REPLAY");
                }
                return false;
              }
              BudgetResourceVector active = activeExposure(runId);
              BudgetResourceVector next = active.plus(requested);
              if (!next.fitsWithin(configuredLimit)) {
                throw new IllegalStateException("ACTION_BUDGET_ENVELOPE_EXHAUSTED");
              }
              int updates =
                  jdbc.sql(INSERT_ENVELOPE_SQL)
                      .param("runId", runId)
                      .param("epochId", required(epochId, "epochId"))
                      .param("decisionId", required(decisionId, "decisionId"))
                      .param("envelopeId", envelopeId)
                      .param("workItemId", required(workItemId, "workItemId"))
                      .param("calls", requested.calls())
                      .param("inputTokens", requested.estimatedInputTokens())
                      .param("outputTokens", requested.maxOutputTokens())
                      .param("totalTokens", requested.maxTotalTokens())
                      .param("cost", requested.maxCostUsd())
                      .param("bucket", required(bucket, "bucket"))
                      .param("contentHash", contentHash)
                      .param("version", version)
                      .param("fencingToken", fencingToken)
                      .update();
              return updates == 1;
            });
    return Boolean.TRUE.equals(inserted);
  }

  private BudgetResourceVector activeExposure(String runId) {
    return jdbc.sql(ACTIVE_EXPOSURE_SQL)
        .param("runId", runId)
        .query(
            (result, row) ->
                new BudgetResourceVector(
                    result.getLong("calls"),
                    result.getLong("input_tokens"),
                    result.getLong("output_tokens"),
                    result.getLong("total_tokens"),
                    Objects.requireNonNullElse(result.getBigDecimal("cost"), BigDecimal.ZERO)))
        .single();
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}
