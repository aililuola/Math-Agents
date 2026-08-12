package io.github.aililuola.mathproofmesh.persistence;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class InboxRepository {
  private static final String RECEIVE_SQL =
      """
      INSERT INTO inbox_event (consumer_name, event_id, run_id)
      VALUES (:consumerName, :eventId, :runId)
      ON CONFLICT (consumer_name, event_id) DO NOTHING
      """;

  private static final String PROCESSED_SQL =
      """
      UPDATE inbox_event
      SET processed_at = clock_timestamp(),
          result_payload = CAST(:resultPayload AS jsonb)
      WHERE consumer_name = :consumerName
        AND event_id = :eventId
        AND processed_at IS NULL
      """;

  private final JdbcClient jdbc;

  public InboxRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public boolean receive(String consumerName, String eventId, String runId) {
    return jdbc.sql(RECEIVE_SQL)
            .param("consumerName", consumerName)
            .param("eventId", eventId)
            .param("runId", runId)
            .update()
        == 1;
  }

  public boolean markProcessed(
      String consumerName, String eventId, String resultPayload) {
    return jdbc.sql(PROCESSED_SQL)
            .param("resultPayload", resultPayload)
            .param("consumerName", consumerName)
            .param("eventId", eventId)
            .update()
        == 1;
  }
}
