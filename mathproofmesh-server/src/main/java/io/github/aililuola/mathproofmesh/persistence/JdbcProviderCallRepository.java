package io.github.aililuola.mathproofmesh.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.provider.ProviderCallPlan;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRecord;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.ProviderCallState;
import io.github.aililuola.mathproofmesh.provider.ProviderCallTransition;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcProviderCallRepository implements ProviderCallRepository {
  private static final String SELECT_COLUMNS =
      """
      run_id, call_id, idempotency_key, agent_id, provider, model, stage,
      request_hash, state, COALESCE(input_tokens, 0) AS input_tokens,
      COALESCE(output_tokens, 0) AS output_tokens,
      COALESCE(cost_amount, 0) AS cost_amount, latency_ms,
      request_artifact_hash, response_artifact_hash, request_id, retry_count,
      possible_duplicate_cost, ambiguity_payload::text AS ambiguity_payload,
      applied_at, version, created_at, updated_at
      """;
  private static final String SELECT_FOR_UPDATE =
      "SELECT "
          + SELECT_COLUMNS
          + """
           FROM provider_call
           WHERE run_id = :runId AND call_id = :callId
           FOR UPDATE
           """;
  private static final String SELECT_BY_IDEMPOTENCY_KEY =
      "SELECT "
          + SELECT_COLUMNS
          + """
           FROM provider_call
           WHERE run_id = :runId AND idempotency_key = :idempotencyKey
           """;
  private static final String SELECT_BY_RUN =
      "SELECT "
          + SELECT_COLUMNS
          + """
           FROM provider_call
           WHERE run_id = :runId
           ORDER BY created_at, call_id
           """;
  private static final String SELECT_BY_CALL =
      "SELECT "
          + SELECT_COLUMNS
          + """
           FROM provider_call
           WHERE run_id = :runId AND call_id = :callId
           """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public JdbcProviderCallRepository(
      JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  @Override
  public ProviderCallRecord plan(ProviderCallPlan plan) {
    Objects.requireNonNull(plan, "plan");
    return Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              jdbc.sql(
                      """
                      INSERT INTO provider_call (
                        run_id, call_id, idempotency_key, agent_id, provider,
                        model, stage, request_hash, state, input_tokens,
                        output_tokens, cost_amount, latency_ms,
                        request_artifact_hash, retry_count,
                        possible_duplicate_cost, ambiguity_payload
                      ) VALUES (
                        :runId, :callId, :idempotencyKey, :agentId, :provider,
                        :model, :stage, :requestHash, 'planned', 0,
                        0, 0, 0, :requestArtifactHash, 0, 0, '{}'::jsonb
                      )
                      ON CONFLICT (run_id, idempotency_key) DO NOTHING
                      """)
                  .param("runId", plan.runId())
                  .param("callId", plan.callId())
                  .param("idempotencyKey", plan.idempotencyKey())
                  .param("agentId", plan.agentId())
                  .param("provider", plan.provider())
                  .param("model", plan.model())
                  .param("stage", plan.stage())
                  .param("requestHash", plan.requestHash())
                  .param("requestArtifactHash", plan.requestArtifactHash())
                  .update();
              ProviderCallRecord stored =
                  findByIdempotencyKey(plan.runId(), plan.idempotencyKey())
                      .orElseThrow(
                          () ->
                              new PersistenceException(
                                  "provider call was not persisted"));
              if (!stored.requestHash().equals(plan.requestHash())
                  || !stored.requestArtifactHash()
                      .equals(plan.requestArtifactHash())) {
                throw new IllegalStateException(
                    "idempotency key is already bound to a different request");
              }
              return stored;
            }),
        "provider call transaction result");
  }

  @Override
  public ProviderCallRecord transition(ProviderCallTransition transition) {
    Objects.requireNonNull(transition, "transition");
    ProviderCallRecord current =
        findByCall(transition.runId(), transition.callId())
            .orElseThrow(() -> new IllegalArgumentException("unknown provider call"));
    if (current.state() != transition.expected()) {
      throw stateConflict(transition, current.state());
    }
    String target = databaseState(transition.target());
    int updated =
        jdbc.sql(
                """
                UPDATE provider_call
                SET state = :target,
                    input_tokens = :inputTokens,
                    output_tokens = :outputTokens,
                    cost_amount = :costAmount,
                    latency_ms = :latencyMs,
                    response_artifact_hash = NULLIF(:responseArtifactHash, ''),
                    request_id = NULLIF(:requestId, ''),
                    retry_count = :retryCount,
                    possible_duplicate_cost = :possibleDuplicateCost,
                    ambiguity_payload = CAST(:ambiguityPayload AS jsonb),
                    dispatched_at = CASE
                      WHEN :target = 'dispatched'
                        THEN COALESCE(dispatched_at, clock_timestamp())
                      ELSE dispatched_at
                    END,
                    completed_at = CASE
                      WHEN :terminal THEN COALESCE(completed_at, clock_timestamp())
                      ELSE completed_at
                    END,
                    version = version + 1,
                    updated_at = clock_timestamp()
                WHERE run_id = :runId
                  AND call_id = :callId
                  AND state = :expected
                  AND version = :version
                """)
            .param("target", target)
            .param("inputTokens", transition.inputTokens())
            .param("outputTokens", transition.outputTokens())
            .param("costAmount", transition.costUsd())
            .param("latencyMs", BigDecimal.valueOf(transition.latencyMs()))
            .param("responseArtifactHash", nullToEmpty(transition.responseArtifactHash()))
            .param("requestId", nullToEmpty(transition.requestId()))
            .param("retryCount", transition.retryCount())
            .param("possibleDuplicateCost", transition.possibleDuplicateCostUsd())
            .param(
                "ambiguityPayload",
                ContractObjectMapper.write(transition.ambiguityPayload()))
            .param("terminal", transition.target().terminal())
            .param("runId", transition.runId())
            .param("callId", transition.callId())
            .param("expected", databaseState(transition.expected()))
            .param("version", current.version())
            .update();
    if (updated != 1) {
      ProviderCallState actual =
          findByCall(transition.runId(), transition.callId())
              .map(ProviderCallRecord::state)
              .orElseThrow(() -> new IllegalArgumentException("unknown provider call"));
      throw stateConflict(transition, actual);
    }
    return findByCall(transition.runId(), transition.callId()).orElseThrow();
  }

  @Override
  public boolean markApplied(
      String runId, String callId, String applicationKey) {
    requireText(runId, "runId");
    requireText(callId, "callId");
    requireText(applicationKey, "applicationKey");
    return Boolean.TRUE.equals(
        transactions.execute(
            ignored -> {
              ProviderCallRecord current =
                  jdbc.sql(SELECT_FOR_UPDATE)
                      .param("runId", runId)
                      .param("callId", callId)
                      .query(JdbcProviderCallRepository::map)
                      .optional()
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "unknown provider call"));
              if (current.state() != ProviderCallState.SUCCEEDED) {
                throw new IllegalStateException(
                    "only a succeeded provider call can be applied");
              }
              int inserted =
                  jdbc.sql(
                          """
                          INSERT INTO provider_call_application (
                            run_id, application_key, call_id
                          ) VALUES (:runId, :applicationKey, :callId)
                          ON CONFLICT DO NOTHING
                          """)
                      .param("runId", runId)
                      .param("applicationKey", applicationKey)
                      .param("callId", callId)
                      .update();
              if (inserted == 0) {
                return false;
              }
              int updated =
                  jdbc.sql(
                          """
                          UPDATE provider_call
                          SET applied_at = clock_timestamp(),
                              version = version + 1,
                              updated_at = clock_timestamp()
                          WHERE run_id = :runId
                            AND call_id = :callId
                            AND state = 'succeeded'
                            AND version = :version
                          """)
                      .param("runId", runId)
                      .param("callId", callId)
                      .param("version", current.version())
                      .update();
              if (updated != 1) {
                throw new OptimisticLockException(callId, current.version());
              }
              return true;
            }));
  }

  @Override
  public Optional<ProviderCallRecord> findByIdempotencyKey(
      String runId, String idempotencyKey) {
    return jdbc.sql(SELECT_BY_IDEMPOTENCY_KEY)
        .param("runId", runId)
        .param("idempotencyKey", idempotencyKey)
        .query(JdbcProviderCallRepository::map)
        .optional();
  }

  @Override
  public List<ProviderCallRecord> findByRun(String runId) {
    return jdbc.sql(SELECT_BY_RUN)
        .param("runId", runId)
        .query(JdbcProviderCallRepository::map)
        .list();
  }

  private Optional<ProviderCallRecord> findByCall(
      String runId, String callId) {
    return jdbc.sql(SELECT_BY_CALL)
        .param("runId", runId)
        .param("callId", callId)
        .query(JdbcProviderCallRepository::map)
        .optional();
  }

  private static ProviderCallRecord map(ResultSet result, int row)
      throws SQLException {
    return new ProviderCallRecord(
        result.getString("run_id"),
        result.getString("call_id"),
        result.getString("idempotency_key"),
        result.getString("agent_id"),
        result.getString("provider"),
        result.getString("model"),
        result.getString("stage"),
        result.getString("request_hash"),
        ProviderCallState.valueOf(
            result.getString("state").toUpperCase(Locale.ROOT)),
        result.getLong("input_tokens"),
        result.getLong("output_tokens"),
        result.getBigDecimal("cost_amount"),
        result.getDouble("latency_ms"),
        result.getString("request_artifact_hash"),
        result.getString("response_artifact_hash"),
        result.getString("request_id"),
        result.getInt("retry_count"),
        result.getBigDecimal("possible_duplicate_cost"),
        parseJson(result.getString("ambiguity_payload")),
        instant(result, "applied_at"),
        result.getLong("version"),
        instant(result, "created_at"),
        instant(result, "updated_at"));
  }

  private static JsonNode parseJson(String value) {
    return ContractObjectMapper.parseTree(value);
  }

  private static Instant instant(ResultSet result, String column)
      throws SQLException {
    Timestamp timestamp = result.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static IllegalStateException stateConflict(
      ProviderCallTransition transition, ProviderCallState actual) {
    return new IllegalStateException(
        "provider call state conflict: expected "
            + transition.expected()
            + " but was "
            + actual);
  }

  private static String databaseState(ProviderCallState state) {
    return state.name().toLowerCase(Locale.ROOT);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
