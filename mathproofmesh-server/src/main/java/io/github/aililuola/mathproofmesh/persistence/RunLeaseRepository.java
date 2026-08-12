package io.github.aililuola.mathproofmesh.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public final class RunLeaseRepository {
  private static final String ACQUIRE_SQL =
      """
      INSERT INTO run_lease (
        run_id, owner_id, fencing_token, expires_at, heartbeat_at
      ) VALUES (
        :runId, :ownerId, 1,
        clock_timestamp() + (:leaseMillis * interval '1 millisecond'),
        clock_timestamp()
      )
      ON CONFLICT (run_id) DO UPDATE
      SET owner_id = EXCLUDED.owner_id,
          fencing_token = CASE
            WHEN run_lease.owner_id = EXCLUDED.owner_id
             AND run_lease.expires_at > clock_timestamp()
              THEN run_lease.fencing_token
            ELSE run_lease.fencing_token + 1
          END,
          expires_at =
            clock_timestamp() + (:leaseMillis * interval '1 millisecond'),
          heartbeat_at = clock_timestamp()
      WHERE run_lease.expires_at <= clock_timestamp()
         OR run_lease.owner_id = EXCLUDED.owner_id
      RETURNING fencing_token
      """;

  private static final String FIND_SQL =
      """
      SELECT run_id, owner_id, fencing_token, expires_at, heartbeat_at
      FROM run_lease
      WHERE run_id = :runId
      """;

  private static final String RENEW_SQL =
      """
      UPDATE run_lease
      SET expires_at =
            clock_timestamp() + (:leaseMillis * interval '1 millisecond'),
          heartbeat_at = clock_timestamp()
      WHERE run_id = :runId
        AND owner_id = :ownerId
        AND fencing_token = :fencingToken
        AND expires_at > clock_timestamp()
      """;

  private static final String RELEASE_SQL =
      """
      UPDATE run_lease
      SET expires_at = clock_timestamp(),
          heartbeat_at = clock_timestamp()
      WHERE run_id = :runId
        AND owner_id = :ownerId
        AND fencing_token = :fencingToken
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public RunLeaseRepository(JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  public RunLease acquire(String runId, String ownerId, Duration duration) {
    requirePositive(duration);
    return Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              Optional<Long> token =
                  jdbc.sql(ACQUIRE_SQL)
                      .param("runId", runId)
                      .param("ownerId", ownerId)
                      .param("leaseMillis", duration.toMillis())
                      .query(Long.class)
                      .optional();
              if (token.isEmpty()) {
                throw new LeaseConflictException(runId);
              }
              jdbc.sql(
                      """
                      UPDATE run
                      SET fencing_token = :fencingToken,
                          updated_at = clock_timestamp()
                      WHERE run_id = :runId
                      """)
                  .param("fencingToken", token.orElseThrow())
                  .param("runId", runId)
                  .update();
              return require(runId);
            }),
        "lease transaction result");
  }

  public RunLease renew(
      String runId, String ownerId, long fencingToken, Duration duration) {
    requirePositive(duration);
    int updated =
        jdbc.sql(RENEW_SQL)
            .param("leaseMillis", duration.toMillis())
            .param("runId", runId)
            .param("ownerId", ownerId)
            .param("fencingToken", fencingToken)
            .update();
    if (updated != 1) {
      throw new LeaseConflictException(runId);
    }
    return require(runId);
  }

  public boolean release(String runId, String ownerId, long fencingToken) {
    return jdbc.sql(RELEASE_SQL)
            .param("runId", runId)
            .param("ownerId", ownerId)
            .param("fencingToken", fencingToken)
            .update()
        == 1;
  }

  public Optional<RunLease> find(String runId) {
    return jdbc.sql(FIND_SQL)
        .param("runId", runId)
        .query(RunLeaseRepository::mapLease)
        .optional();
  }

  public RunLease require(String runId) {
    return find(runId)
        .orElseThrow(() -> new PersistenceException("unknown run lease '" + runId + "'"));
  }

  private static RunLease mapLease(ResultSet result, int rowNumber) throws SQLException {
    return new RunLease(
        result.getString("run_id"),
        result.getString("owner_id"),
        result.getLong("fencing_token"),
        result.getObject("expires_at", OffsetDateTime.class).toInstant(),
        result.getObject("heartbeat_at", OffsetDateTime.class).toInstant());
  }

  private static void requirePositive(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("lease duration must be positive");
    }
  }
}
