package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class Phase17CheckpointOutboxPerformanceIT {
  private static final int CASES = 1_000;
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
  private static final String RUN_ID = "phase17-performance";
  private static final String HASH = "8".repeat(64);

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(POSTGRES_IMAGE)
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("mathproofmesh")
          .withUsername("mathproofmesh")
          .withPassword("phase17-fake-password");

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static SingleConnectionDataSource dataSource;

  @BeforeAll
  static void migrate() {
    dataSource =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
    jdbc = JdbcClient.create(dataSource);
    transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @AfterAll
  static void closeDataSource() {
    dataSource.destroy();
  }

  @Test
  void thousandCheckpointsAndOutboxRetriesAreMeasured() throws Exception {
    RunRepository runs = new RunRepository(jdbc);
    runs.create(RUN_ID, HASH, "created", "preflight", "{}");
    jdbc.sql(
            """
            INSERT INTO strategy (run_id, strategy_id, status, payload)
            VALUES (:runId, 'strategy-a', 'active', '{}'::jsonb)
            """)
        .param("runId", RUN_ID)
        .update();
    jdbc.sql(
            """
            INSERT INTO route (
              run_id, route_id, strategy_id, status, latest_checkpoint_id
            ) VALUES (
              :runId, 'route-a', 'strategy-a', 'active', 'verified-0'
            )
            """)
        .param("runId", RUN_ID)
        .update();
    RunLease lease =
        new RunLeaseRepository(jdbc, transactions)
            .acquire(RUN_ID, "phase17-worker", Duration.ofMinutes(5));

    CheckpointRepository checkpoints = new CheckpointRepository(jdbc);
    long checkpointStarted = System.nanoTime();
    for (int index = 0; index < CASES; index++) {
      checkpoints.saveWorking(
          RUN_ID,
          "working-" + index,
          "route-a",
          "strategy-a",
          "verified-0",
          index + 1,
          "pending_review",
          "{\"index\":" + index + "}",
          lease.ownerId(),
          lease.fencingToken());
    }
    long checkpointNanos = System.nanoTime() - checkpointStarted;

    TransactionalEventStore events =
        new TransactionalEventStore(transactions, new EventLogRepository(jdbc));
    long outboxInsertStarted = System.nanoTime();
    for (int index = 0; index < CASES; index++) {
      int ordinal = index;
      events.commit(
          () -> {},
          DomainEvent.create(
              "phase17-event-" + ordinal,
              RUN_ID,
              "checkpoint",
              "working-" + ordinal,
              ordinal + 1L,
              "checkpoint.retry.requested",
              "{\"index\":" + ordinal + "}"));
    }
    long outboxInsertNanos = System.nanoTime() - outboxInsertStarted;

    OutboxRepository outbox = new OutboxRepository(jdbc, transactions);
    long retryStarted = System.nanoTime();
    var first = outbox.claimBatch("publisher-a", CASES, Duration.ofMinutes(5));
    assertThat(first).hasSize(CASES);
    for (OutboxRecord record : first) {
      assertThat(
              outbox.releaseAfterFailure(
                  record.eventId(), "publisher-a", "injected retry", Duration.ZERO))
          .isTrue();
    }
    var retried = outbox.claimBatch("publisher-b", CASES, Duration.ofMinutes(5));
    assertThat(retried).hasSize(CASES);
    assertThat(retried).allMatch(record -> record.attempts() == 2);
    for (OutboxRecord record : retried) {
      assertThat(outbox.markPublished(record.eventId(), "publisher-b")).isTrue();
    }
    long retryNanos = System.nanoTime() - retryStarted;

    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM working_checkpoint")
                .query(Long.class)
                .single())
        .isEqualTo(CASES);
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NOT NULL")
                .query(Long.class)
                .single())
        .isEqualTo(CASES);

    Path report =
        Path.of(System.getProperty("mathproofmesh.projectRoot"))
            .resolve("target/benchmark-reports/phase17-checkpoint-outbox.json");
    Files.createDirectories(report.getParent());
    Files.writeString(
        report,
        """
        {
          "scenario":"1000-checkpoint-outbox-retry",
          "cases":%d,
          "checkpoint_elapsed_ns":%d,
          "outbox_insert_elapsed_ns":%d,
          "claim_release_reclaim_publish_elapsed_ns":%d,
          "retry_attempts_per_event":2,
          "result":"PASS"
        }
        """
            .formatted(CASES, checkpointNanos, outboxInsertNanos, retryNanos),
        StandardCharsets.UTF_8);
  }
}
