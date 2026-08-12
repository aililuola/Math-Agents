package io.github.aililuola.mathproofmesh.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public final class OutboxRepository {
  private static final String CLAIM_SQL =
      """
      WITH candidates AS (
        SELECT event_id
        FROM outbox_event
        WHERE published_at IS NULL
          AND available_at <= clock_timestamp()
          AND (
            claimed_at IS NULL
            OR claimed_at <
              clock_timestamp() - (:claimTimeoutMillis * interval '1 millisecond')
          )
        ORDER BY available_at, event_id
        FOR UPDATE SKIP LOCKED
        LIMIT :batchSize
      )
      UPDATE outbox_event AS target
      SET claimed_by = :workerId,
          claimed_at = clock_timestamp(),
          attempts = target.attempts + 1
      FROM candidates
      WHERE target.event_id = candidates.event_id
      RETURNING target.event_id, target.run_id, target.aggregate_type,
                target.aggregate_id, target.aggregate_version, target.event_type,
                target.payload::text, target.available_at, target.claimed_by,
                target.claimed_at, target.published_at, target.attempts
      """;

  private static final String PUBLISH_SQL =
      """
      UPDATE outbox_event
      SET published_at = clock_timestamp(),
          claimed_by = NULL,
          claimed_at = NULL,
          last_error = NULL
      WHERE event_id = :eventId
        AND claimed_by = :workerId
        AND published_at IS NULL
      """;

  private static final String FAILURE_SQL =
      """
      UPDATE outbox_event
      SET claimed_by = NULL,
          claimed_at = NULL,
          last_error = :lastError,
          available_at =
            clock_timestamp() + (:retryMillis * interval '1 millisecond')
      WHERE event_id = :eventId
        AND claimed_by = :workerId
        AND published_at IS NULL
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public OutboxRepository(JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  public List<OutboxRecord> claimBatch(
      String workerId, int batchSize, Duration claimTimeout) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    Objects.requireNonNull(claimTimeout, "claimTimeout");
    return Objects.requireNonNull(
        transactions.execute(
            ignored ->
                jdbc.sql(CLAIM_SQL)
                    .param("claimTimeoutMillis", claimTimeout.toMillis())
                    .param("batchSize", batchSize)
                    .param("workerId", workerId)
                    .query(OutboxRepository::mapRecord)
                    .list()),
        "outbox claim transaction result");
  }

  public boolean markPublished(String eventId, String workerId) {
    return jdbc.sql(PUBLISH_SQL)
            .param("eventId", eventId)
            .param("workerId", workerId)
            .update()
        == 1;
  }

  public boolean releaseAfterFailure(
      String eventId, String workerId, String lastError, Duration retryDelay) {
    return jdbc.sql(FAILURE_SQL)
            .param("eventId", eventId)
            .param("workerId", workerId)
            .param("lastError", lastError)
            .param("retryMillis", retryDelay.toMillis())
            .update()
        == 1;
  }

  private static OutboxRecord mapRecord(ResultSet result, int rowNumber)
      throws SQLException {
    OffsetDateTime claimed = result.getObject("claimed_at", OffsetDateTime.class);
    OffsetDateTime published = result.getObject("published_at", OffsetDateTime.class);
    return new OutboxRecord(
        result.getString("event_id"),
        result.getString("run_id"),
        result.getString("aggregate_type"),
        result.getString("aggregate_id"),
        result.getLong("aggregate_version"),
        result.getString("event_type"),
        result.getString("payload"),
        result.getObject("available_at", OffsetDateTime.class).toInstant(),
        result.getString("claimed_by"),
        claimed == null ? null : claimed.toInstant(),
        published == null ? null : published.toInstant(),
        result.getInt("attempts"));
  }
}
