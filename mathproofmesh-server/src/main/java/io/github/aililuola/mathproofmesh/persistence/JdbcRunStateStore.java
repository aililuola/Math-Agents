package io.github.aililuola.mathproofmesh.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.runstate.RunReconciliationStatus;
import io.github.aililuola.mathproofmesh.runstate.RunStateSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunStateStore;
import io.github.aililuola.mathproofmesh.runstate.RunStateTransition;
import io.github.aililuola.mathproofmesh.runstate.RunStateTransitionLedger;
import io.github.aililuola.mathproofmesh.runstate.RunStateTransitionSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunStateTransitionTrigger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL run-state authority committed with legacy projection and outbox event. */
public final class JdbcRunStateStore implements RunStateStore {
  private static final String LOAD_SQL =
      "SELECT state_payload::text FROM run_state_snapshot WHERE run_id = :runId";
  private static final String UPSERT_AUTHORITY_SQL =
      """
      INSERT INTO run_state_snapshot (
        run_id, authority_sequence, execution_attempt_id, execution_status,
        math_status, usage_status, campaign_status, report_status,
        authority_hash, state_hash, state_payload, version
      ) VALUES (
        :runId, :sequence, :attemptId, :execution, :math, :usage, :campaign,
        :report, :authorityHash, :stateHash, CAST(:payload AS jsonb), :version
      )
      ON CONFLICT (run_id) DO UPDATE SET
        authority_sequence = EXCLUDED.authority_sequence,
        execution_attempt_id = EXCLUDED.execution_attempt_id,
        execution_status = EXCLUDED.execution_status,
        math_status = EXCLUDED.math_status,
        usage_status = EXCLUDED.usage_status,
        campaign_status = EXCLUDED.campaign_status,
        report_status = EXCLUDED.report_status,
        authority_hash = EXCLUDED.authority_hash,
        state_hash = EXCLUDED.state_hash,
        state_payload = EXCLUDED.state_payload,
        version = EXCLUDED.version,
        updated_at = clock_timestamp()
      WHERE run_state_snapshot.version = :expectedVersion
      """;
  private static final String UPSERT_PROJECTION_SQL =
      """
      INSERT INTO run_state_snapshot (
        run_id, authority_sequence, execution_attempt_id, execution_status,
        math_status, usage_status, campaign_status, report_status,
        authority_hash, state_hash, state_payload, version
      ) VALUES (
        :runId, :sequence, :attemptId, :execution, :math, :usage, :campaign,
        :report, :authorityHash, :stateHash, CAST(:payload AS jsonb), :version
      )
      ON CONFLICT (run_id) DO UPDATE SET
        authority_sequence = EXCLUDED.authority_sequence,
        execution_attempt_id = EXCLUDED.execution_attempt_id,
        execution_status = EXCLUDED.execution_status,
        math_status = EXCLUDED.math_status,
        usage_status = EXCLUDED.usage_status,
        campaign_status = EXCLUDED.campaign_status,
        report_status = EXCLUDED.report_status,
        authority_hash = EXCLUDED.authority_hash,
        state_hash = EXCLUDED.state_hash,
        state_payload = EXCLUDED.state_payload,
        version = EXCLUDED.version,
        updated_at = clock_timestamp()
      WHERE run_state_snapshot.state_hash = :expectedStateHash
      """;
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

  public JdbcRunStateStore(JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  @Override
  public Optional<RunStateSnapshot> load(String runId) {
    return jdbc.sql(LOAD_SQL)
        .param("runId", runId)
        .query(String.class)
        .optional()
        .map(this::decodeState);
  }

  @Override
  public RunStateSnapshot compareAndSet(
      String runId,
      long expectedVersion,
      RunStateSnapshot next,
      String ownerId,
      long fencingToken) {
    Objects.requireNonNull(next, "next");
    return Objects.requireNonNull(
        transactions.execute(
            ignored ->
                commit(
                    runId,
                    expectedVersion,
                    "",
                    -1L,
                    next,
                    ownerId,
                    fencingToken,
                    false)),
        "run state transaction result");
  }

  @Override
  public RunStateSnapshot compareAndSetProjection(
      String runId,
      String expectedStateHash,
      long expectedProjectionVersion,
      RunStateSnapshot next,
      String ownerId,
      long fencingToken) {
    Objects.requireNonNull(next, "next");
    return Objects.requireNonNull(
        transactions.execute(
            ignored ->
                commit(
                    runId,
                    -1L,
                    expectedStateHash,
                    expectedProjectionVersion,
                    next,
                    ownerId,
                    fencingToken,
                    true)),
        "run state projection transaction result");
  }

  private RunStateSnapshot commit(
      String runId,
      long expectedVersion,
      String expectedStateHash,
      long expectedProjectionVersion,
      RunStateSnapshot next,
      String ownerId,
      long fencingToken,
      boolean projectionOnly) {
    if (!runId.equals(next.authority().runId())) {
      throw new IllegalArgumentException("run state identity mismatch");
    }
    long leaseCount =
        jdbc.sql(
                """
                SELECT count(*) FROM run_lease
                WHERE run_id = :runId AND owner_id = :ownerId
                  AND fencing_token = :fencingToken AND expires_at > clock_timestamp()
                """)
            .param("runId", runId)
            .param("ownerId", ownerId)
            .param("fencingToken", fencingToken)
            .query(Long.class)
            .single();
    if (leaseCount != 1L) {
      throw new LeaseConflictException(runId);
    }
    RunStateSnapshot previous = load(runId).orElse(null);
    long actualVersion = previous == null ? -1L : previous.authority().version();
    if (projectionOnly) {
      if (previous == null
          || !equalHash(previous.stateHash(), expectedStateHash)
          || previous.projection().projectionVersion() != expectedProjectionVersion
          || !equalHash(
              previous.authority().authorityHash(), next.authority().authorityHash())
          || previous.authority().version() != next.authority().version()
          || next.projection().projectionVersion() != expectedProjectionVersion + 1L) {
        throw new OptimisticLockException(runId, actualVersion);
      }
    } else if (actualVersion != expectedVersion) {
      throw new OptimisticLockException(runId, expectedVersion);
    } else if (previous != null
        && equalHash(previous.authority().authorityHash(), next.authority().authorityHash())
        && !equalHash(previous.stateHash(), next.stateHash())) {
      throw new IllegalStateException("projection-only update requires projection compare-and-set");
    }
    String json = encode(next);
    var upsert =
        projectionOnly ? jdbc.sql(UPSERT_PROJECTION_SQL) : jdbc.sql(UPSERT_AUTHORITY_SQL);
    int updated =
        upsert
            .param("runId", runId)
            .param("sequence", next.authority().authoritySequence())
            .param("attemptId", next.authority().executionAttemptId())
            .param("execution", next.authority().executionStatus().name())
            .param("math", next.authority().mathStatus().name())
            .param("usage", next.authority().usageStatus().name())
            .param("campaign", next.authority().campaignStatus().name())
            .param("report", next.projection().reportStatus().name())
            .param("authorityHash", next.authority().authorityHash())
            .param("stateHash", next.stateHash())
            .param("payload", json)
            .param("version", next.authority().version())
            .param(
                projectionOnly ? "expectedStateHash" : "expectedVersion",
                projectionOnly ? expectedStateHash : expectedVersion)
            .update();
    /*
     * SQL remains a static, reviewable text block; the chosen CAS frontier only changes its
     * final predicate.
     */
    if (updated != 1) {
      throw new OptimisticLockException(runId, expectedVersion);
    }
    String compatibleStatus = compatibleStatus(next);
    int runUpdated =
        jdbc.sql(
                """
                UPDATE run SET status = :status, current_stage = :stage,
                  version = version + 1, updated_at = clock_timestamp()
                WHERE run_id = :runId AND fencing_token = :fencingToken
                """)
            .param("status", compatibleStatus)
            .param("stage", next.authority().currentStage())
            .param("runId", runId)
            .param("fencingToken", fencingToken)
            .update();
    if (runUpdated != 1) {
      throw new LeaseConflictException(runId);
    }
    if (previous == null || !equalHash(previous.stateHash(), next.stateHash())) {
      RunStateTransition transition = transition(previous, next);
      jdbc.sql(
              """
              INSERT INTO run_state_transition (
                run_id, transition_id, sequence, from_state_hash, to_state_hash,
                trigger, payload, created_at
              ) VALUES (
                :runId, :transitionId, :sequence, :fromHash, :toHash,
                :trigger, CAST(:payload AS jsonb), :createdAt
              )
              """)
          .param("runId", runId)
          .param("transitionId", transition.transitionId())
          .param("sequence", transition.sequence())
          .param("fromHash", transition.fromStateHash())
          .param("toHash", transition.toStateHash())
          .param("trigger", transition.trigger().name())
          .param("payload", encode(transition.payload()))
          .param("createdAt", transition.createdAt().atOffset(java.time.ZoneOffset.UTC))
          .update();
      String eventId = "run-state-" + CanonicalJson.stableHash(List.of(runId, next.stateHash()));
      jdbc.sql(
              """
              INSERT INTO outbox_event (
                event_id, run_id, aggregate_type, aggregate_id, aggregate_version,
                event_type, payload
              ) VALUES (
                :eventId, :runId, 'run_state', :runId, :version,
                'run_state_committed', CAST(:payload AS jsonb)
              ) ON CONFLICT (event_id) DO NOTHING
              """)
          .param("eventId", eventId)
          .param("runId", runId)
          .param("version", next.authority().version())
          .param("payload", json)
          .update();
    }
    return next;
  }

  @Override
  public List<RunStateTransition> transitions(String runId) {
    return jdbc.sql(
            """
            SELECT transition_id, run_id, sequence, from_state_hash, to_state_hash,
                   trigger, payload::text, created_at
            FROM run_state_transition WHERE run_id = :runId ORDER BY sequence
            """)
        .param("runId", runId)
        .query(this::mapTransition)
        .list();
  }

  private RunStateTransition transition(RunStateSnapshot previous, RunStateSnapshot next) {
    RunStateTransitionLedger ledger = new RunStateTransitionLedger();
    ledger.restore(new RunStateTransitionSnapshot(transitions(next.authority().runId()), null));
    return ledger.append(
        next.authority().runId(),
        previous == null ? "" : previous.stateHash(),
        next.stateHash(),
        previous == null
            ? RunStateTransitionTrigger.RUN_CREATED
            : next.reconciliationStatus() == RunReconciliationStatus.REPAIRED
                ? RunStateTransitionTrigger.RECONCILIATION_REPAIRED
                : RunStateTransitionTrigger.USAGE_RECONCILED,
        Map.of("authority_hash", next.authority().authorityHash()),
        next.updatedAt());
  }

  private RunStateTransition mapTransition(ResultSet result, int row) throws SQLException {
    try {
      @SuppressWarnings("unchecked")
      Map<String, String> payload = mapper.readValue(result.getString("payload"), Map.class);
      return new RunStateTransition(
          result.getString("transition_id"),
          result.getString("run_id"),
          result.getLong("sequence"),
          result.getString("from_state_hash"),
          result.getString("to_state_hash"),
          RunStateTransitionTrigger.valueOf(result.getString("trigger")),
          payload,
          result.getObject("created_at", OffsetDateTime.class).toInstant());
    } catch (java.io.IOException exception) {
      throw new SQLException("run state transition payload is invalid", exception);
    }
  }

  private RunStateSnapshot decodeState(String json) {
    try {
      return mapper.readValue(json, RunStateSnapshot.class);
    } catch (java.io.IOException exception) {
      throw new PersistenceException("run state payload is invalid", exception);
    }
  }

  private String encode(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new PersistenceException("run state payload could not be serialized", exception);
    }
  }

  private static String compatibleStatus(RunStateSnapshot state) {
    return switch (state.authority().campaignStatus()) {
      case ACTIVE -> "running";
      case QUEUED -> "queued";
      case RECOVERABLE -> "interrupted";
      case ARCHIVED -> "archived";
      case TERMINAL ->
          state.authority().mathStatus()
                  == io.github.aililuola.mathproofmesh.runstate.RunMathematicalStatus.VERIFIED
              ? "completed"
              : "unverified";
    };
  }

  private static boolean equalHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}
