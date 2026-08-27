package io.github.aililuola.mathproofmesh.persistence;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class EventLogRepository {
  private static final String LOCK_RUN_SQL =
      """
      SELECT run_id
      FROM run
      WHERE run_id = :runId
      FOR UPDATE
      """;

  private static final String NEXT_SEQUENCE_SQL =
      """
      SELECT COALESCE(MAX(sequence), 0) + 1
      FROM event_log
      WHERE run_id = :runId
      """;

  private static final String INSERT_EVENT_SQL =
      """
      INSERT INTO event_log (
        run_id, sequence, event_id, aggregate_type, aggregate_id,
        event_type, aggregate_version, payload, event_hash
      ) VALUES (
        :runId, :sequence, :eventId, :aggregateType, :aggregateId,
        :eventType, :aggregateVersion, CAST(:payload AS jsonb), :eventHash
      )
      """;

  private static final String INSERT_OUTBOX_SQL =
      """
      INSERT INTO outbox_event (
        event_id, run_id, aggregate_type, aggregate_id,
        aggregate_version, event_type, payload
      ) VALUES (
        :eventId, :runId, :aggregateType, :aggregateId,
        :aggregateVersion, :eventType, CAST(:payload AS jsonb)
      )
      """;

  private final JdbcClient jdbc;

  public EventLogRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public long appendWithOutbox(DomainEvent event) {
    String lockedRun =
        jdbc.sql(LOCK_RUN_SQL)
            .param("runId", event.runId())
            .query(String.class)
            .single();
    if (!lockedRun.equals(event.runId())) {
      throw new PersistenceException("failed to lock run '" + event.runId() + "'");
    }
    long sequence =
        jdbc.sql(NEXT_SEQUENCE_SQL)
            .param("runId", event.runId())
            .query(Long.class)
            .single();
    jdbc.sql(INSERT_EVENT_SQL)
        .param("runId", event.runId())
        .param("sequence", sequence)
        .param("eventId", event.eventId())
        .param("aggregateType", event.aggregateType())
        .param("aggregateId", event.aggregateId())
        .param("eventType", event.eventType())
        .param("aggregateVersion", event.aggregateVersion())
        .param("payload", event.payload())
        .param("eventHash", event.eventHash())
        .update();
    jdbc.sql(INSERT_OUTBOX_SQL)
        .param("eventId", event.eventId())
        .param("runId", event.runId())
        .param("aggregateType", event.aggregateType())
        .param("aggregateId", event.aggregateId())
        .param("aggregateVersion", event.aggregateVersion())
        .param("eventType", event.eventType())
        .param("payload", event.payload())
        .update();
    return sequence;
  }
}
