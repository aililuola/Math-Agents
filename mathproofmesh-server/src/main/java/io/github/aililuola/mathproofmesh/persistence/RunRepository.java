package io.github.aililuola.mathproofmesh.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class RunRepository {
  private static final String FIND_SQL =
      """
      SELECT run_id, problem_hash, status, current_stage, config_payload::text,
             fencing_token, version, created_at, updated_at
      FROM run
      WHERE run_id = :runId
      """;

  private static final String INSERT_SQL =
      """
      INSERT INTO run (
        run_id, problem_hash, status, current_stage, config_payload
      ) VALUES (
        :runId, :problemHash, :status, :currentStage,
        CAST(:configPayload AS jsonb)
      )
      """;

  private static final String UPDATE_STAGE_SQL =
      """
      UPDATE run AS target
      SET status = :status,
          current_stage = :currentStage,
          version = target.version + 1,
          updated_at = clock_timestamp()
      WHERE target.run_id = :runId
        AND target.version = :expectedVersion
        AND target.fencing_token = :fencingToken
        AND EXISTS (
          SELECT 1
          FROM run_lease AS lease
          WHERE lease.run_id = target.run_id
            AND lease.owner_id = :ownerId
            AND lease.fencing_token = :fencingToken
            AND lease.expires_at > clock_timestamp()
        )
      """;

  private final JdbcClient jdbc;

  public RunRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public void create(
      String runId,
      String problemHash,
      String status,
      String currentStage,
      String configPayload) {
    jdbc.sql(INSERT_SQL)
        .param("runId", runId)
        .param("problemHash", problemHash)
        .param("status", status)
        .param("currentStage", currentStage)
        .param("configPayload", configPayload)
        .update();
  }

  public Optional<RunRecord> find(String runId) {
    return jdbc.sql(FIND_SQL)
        .param("runId", runId)
        .query(RunRepository::mapRun)
        .optional();
  }

  public RunRecord require(String runId) {
    return find(runId)
        .orElseThrow(() -> new PersistenceException("unknown run '" + runId + "'"));
  }

  public RunRecord updateStage(
      String runId,
      String status,
      String currentStage,
      long expectedVersion,
      String ownerId,
      long fencingToken) {
    int updated =
        jdbc.sql(UPDATE_STAGE_SQL)
            .param("status", status)
            .param("currentStage", currentStage)
            .param("runId", runId)
            .param("expectedVersion", expectedVersion)
            .param("ownerId", ownerId)
            .param("fencingToken", fencingToken)
            .update();
    if (updated != 1) {
      throw new OptimisticLockException(runId, expectedVersion);
    }
    return require(runId);
  }

  private static RunRecord mapRun(ResultSet result, int rowNumber) throws SQLException {
    return new RunRecord(
        result.getString("run_id"),
        result.getString("problem_hash"),
        result.getString("status"),
        result.getString("current_stage"),
        result.getString("config_payload"),
        result.getLong("fencing_token"),
        result.getLong("version"),
        result.getObject("created_at", OffsetDateTime.class).toInstant(),
        result.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
