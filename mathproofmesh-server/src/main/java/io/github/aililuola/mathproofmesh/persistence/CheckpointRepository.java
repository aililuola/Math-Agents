package io.github.aililuola.mathproofmesh.persistence;

import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class CheckpointRepository {
  private static final String SAVE_WORKING_SQL =
      """
      INSERT INTO working_checkpoint (
        run_id, working_checkpoint_id, path_id, strategy_id,
        parent_verified_checkpoint_id, segment_index, status, payload
      )
      SELECT
        :runId, :workingCheckpointId, :pathId, :strategyId,
        :parentCheckpointId, :segmentIndex, :status, CAST(:payload AS jsonb)
      FROM run_lease
      WHERE run_id = :runId
        AND owner_id = :ownerId
        AND fencing_token = :fencingToken
        AND expires_at > clock_timestamp()
      """;

  private static final String LATEST_VERIFIED_SQL =
      """
      SELECT latest_checkpoint_id
      FROM route
      WHERE run_id = :runId
        AND route_id = :routeId
      """;

  private final JdbcClient jdbc;

  public CheckpointRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public void saveWorking(
      String runId,
      String workingCheckpointId,
      String pathId,
      String strategyId,
      String parentCheckpointId,
      int segmentIndex,
      String status,
      String payload,
      String ownerId,
      long fencingToken) {
    int inserted =
        jdbc.sql(SAVE_WORKING_SQL)
            .param("runId", runId)
            .param("workingCheckpointId", workingCheckpointId)
            .param("pathId", pathId)
            .param("strategyId", strategyId)
            .param("parentCheckpointId", parentCheckpointId)
            .param("segmentIndex", segmentIndex)
            .param("status", status)
            .param("payload", payload)
            .param("ownerId", ownerId)
            .param("fencingToken", fencingToken)
            .update();
    if (inserted != 1) {
      throw new LeaseConflictException(runId);
    }
  }

  public Optional<String> latestVerifiedCheckpoint(String runId, String routeId) {
    return jdbc.sql(LATEST_VERIFIED_SQL)
        .param("runId", runId)
        .param("routeId", routeId)
        .query(String.class)
        .optional();
  }
}
