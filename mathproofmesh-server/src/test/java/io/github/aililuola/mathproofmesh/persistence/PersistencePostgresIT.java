package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PersistencePostgresIT {
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
  private static final String HASH = "0".repeat(64);
  private static final Set<String> EXPECTED_TABLES =
      Set.of(
          "run",
          "problem_contract",
          "strategy",
          "route",
          "route_member",
          "proof_attempt",
          "proof_step",
          "claim",
          "claim_dependency",
          "message",
          "message_delivery",
          "message_receipt",
          "message_utility",
          "memory_item",
          "memory_dependency",
          "memory_provenance",
          "memory_invalidation",
          "proof_obligation",
          "proof_graph_edge",
          "proof_graph_state",
          "proof_checkpoint",
          "working_checkpoint",
          "verification_report",
          "referee_claim_ledger",
          "meta_review",
          "proof_control_state",
          "control_action",
          "inspiration_proposal",
          "inspiration_outcome",
          "experiment_spec",
          "experiment_result",
          "computation_cache",
          "artifact",
          "run_artifact",
          "provider_call",
          "provider_call_application",
          "provider_circuit_state",
          "provider_prompt_request",
          "usage_ledger",
          "event_log",
          "outbox_event",
          "inbox_event",
          "run_lease",
          "legacy_import",
          "run_state_snapshot",
          "run_state_transition",
          "research_epoch",
          "research_work_item",
          "agent_lease",
          "concurrency_telemetry_event");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(POSTGRES_IMAGE)
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("mathproofmesh")
          .withUsername("mathproofmesh")
          .withPassword("phase04-test-password");

  private static DriverManagerDataSource dataSource;
  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void migrateMainSchema() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .load()
        .migrate();
    jdbc = JdbcClient.create(dataSource);
    transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @BeforeEach
  void clearRunData() {
    jdbc.sql("TRUNCATE TABLE run, artifact CASCADE").update();
  }

  @Test
  void exactPostgres184ImageRunsFreshMigrationAndRestartIsIdempotent() {
    assertThat(jdbc.sql("SHOW server_version").query(String.class).single())
        .startsWith("18.4");

    Flyway isolated =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas("phase04_empty")
            .defaultSchema("phase04_empty")
            .cleanDisabled(false)
            .validateOnMigrate(true)
            .load();
    isolated.clean();

    assertThat(isolated.migrate().migrationsExecuted).isEqualTo(6);
    Flyway restarted =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas("phase04_empty")
            .defaultSchema("phase04_empty")
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(restarted.migrate().migrationsExecuted).isZero();
    assertThat(restarted.validateWithResult().validationSuccessful).isTrue();
  }

  @Test
  void migrationCreatesEveryRequiredRunIsolatedTable() {
    Set<String> tables =
        Set.copyOf(
            jdbc.sql(
                    """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_type = 'BASE TABLE'
                      AND table_name <> 'flyway_schema_history'
                    """)
                .query(String.class)
                .list());

    assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    assertThat(tables).hasSize(50);
  }

  @Test
  void runQueriesAreIsolatedAndCriticalWritesRequireVersionAndFencingToken() {
    RunRepository runs = new RunRepository(jdbc);
    RunLeaseRepository leases = new RunLeaseRepository(jdbc, transactions);
    createRun(runs, "run-a");
    createRun(runs, "run-b");

    assertThat(runs.require("run-a").runId()).isEqualTo("run-a");
    assertThat(runs.require("run-b").runId()).isEqualTo("run-b");
    RunLease first = leases.acquire("run-a", "worker-a", Duration.ofMinutes(1));
    assertThat(first.fencingToken()).isPositive();
    assertThatThrownBy(
            () -> leases.acquire("run-a", "worker-b", Duration.ofMinutes(1)))
        .isInstanceOf(LeaseConflictException.class);

    RunRecord updated =
        runs.updateStage(
            "run-a",
            "running",
            "proof",
            0,
            first.ownerId(),
            first.fencingToken());
    assertThat(updated.version()).isEqualTo(1);
    assertThat(updated.currentStage()).isEqualTo("proof");
    assertThat(runs.require("run-b").currentStage()).isEqualTo("preflight");
    assertThatThrownBy(
            () ->
                runs.updateStage(
                    "run-a",
                    "running",
                    "verify",
                    0,
                    first.ownerId(),
                    first.fencingToken()))
        .isInstanceOf(OptimisticLockException.class);

    assertThat(leases.release("run-a", first.ownerId(), first.fencingToken()))
        .isTrue();
    RunLease second =
        leases.acquire("run-a", "worker-b", Duration.ofMinutes(1));
    assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
    assertThatThrownBy(
            () ->
                runs.updateStage(
                    "run-a",
                    "running",
                    "verify",
                    updated.version(),
                    first.ownerId(),
                    first.fencingToken()))
        .isInstanceOf(OptimisticLockException.class);
  }

  @Test
  void concurrentLeaseTakeoverHasExactlyOneOwnerAndRejectsStaleWriter()
      throws Exception {
    RunRepository runs = new RunRepository(jdbc);
    RunLeaseRepository leases = new RunLeaseRepository(jdbc, transactions);
    createRun(runs, "run-race");
    RunLease stale =
        leases.acquire("run-race", "worker-old", Duration.ofMinutes(1));
    jdbc.sql(
            """
            UPDATE run_lease
            SET expires_at = clock_timestamp() - interval '1 second'
            WHERE run_id = :runId
            """)
        .param("runId", "run-race")
        .update();

    CountDownLatch start = new CountDownLatch(1);
    List<Callable<RunLease>> racers =
        List.of(
            () -> acquireAfter(start, leases, "worker-b"),
            () -> acquireAfter(start, leases, "worker-c"));
    List<RunLease> winners = new java.util.ArrayList<>();
    int conflicts = 0;
    try (var executor = Executors.newFixedThreadPool(2)) {
      var results = racers.stream().map(executor::submit).toList();
      start.countDown();
      for (var result : results) {
        try {
          winners.add(result.get());
        } catch (java.util.concurrent.ExecutionException exception) {
          assertThat(exception.getCause()).isInstanceOf(LeaseConflictException.class);
          conflicts++;
        }
      }
    }

    assertThat(winners).hasSize(1);
    assertThat(conflicts).isEqualTo(1);
    assertThat(winners.getFirst().fencingToken())
        .isGreaterThan(stale.fencingToken());
    assertThatThrownBy(
            () ->
                runs.updateStage(
                    "run-race",
                    "running",
                    "proof",
                    0,
                    stale.ownerId(),
                    stale.fencingToken()))
        .isInstanceOf(OptimisticLockException.class);
  }

  @Test
  void eventAndOutboxCommitTogetherAndRollbackAtCrashPoint() {
    RunRepository runs = new RunRepository(jdbc);
    createRun(runs, "run-events");
    EventLogRepository events = new EventLogRepository(jdbc);
    TransactionalEventStore store =
        new TransactionalEventStore(transactions, events);
    DomainEvent committed =
        DomainEvent.create(
            "event-1",
            "run-events",
            "run",
            "run-events",
            1,
            "run.started",
            "{\"status\":\"running\"}");

    long sequence =
        store.commit(
            () ->
                jdbc.sql(
                        """
                        UPDATE run
                        SET status = :status
                        WHERE run_id = :runId
                        """)
                    .param("status", "running")
                    .param("runId", "run-events")
                    .update(),
            committed);
    assertThat(sequence).isEqualTo(1);
    assertThat(count("event_log")).isEqualTo(1);
    assertThat(count("outbox_event")).isEqualTo(1);
    assertThat(runs.require("run-events").status()).isEqualTo("running");

    DomainEvent rolledBack =
        DomainEvent.create(
            "event-2",
            "run-events",
            "run",
            "run-events",
            2,
            "run.failed",
            "{\"status\":\"failed\"}");
    assertThatThrownBy(
            () ->
                store.commit(
                    () -> {
                      jdbc.sql(
                              """
                              UPDATE run
                              SET status = :status
                              WHERE run_id = :runId
                              """)
                          .param("status", "failed")
                          .param("runId", "run-events")
                          .update();
                      throw new IllegalStateException("simulated crash");
                    },
                    rolledBack))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated crash");
    assertThat(count("event_log")).isEqualTo(1);
    assertThat(count("outbox_event")).isEqualTo(1);
    assertThat(runs.require("run-events").status()).isEqualTo("running");
  }

  @Test
  void outboxClaimsUseCrashRecoveryAndInboxDeduplicatesConsumerEventPair() {
    RunRepository runs = new RunRepository(jdbc);
    createRun(runs, "run-delivery");
    EventLogRepository events = new EventLogRepository(jdbc);
    TransactionalEventStore store =
        new TransactionalEventStore(transactions, events);
    store.commit(
        () -> {},
        DomainEvent.create(
            "event-a",
            "run-delivery",
            "run",
            "run-delivery",
            1,
            "one",
            "{}"));
    store.commit(
        () -> {},
        DomainEvent.create(
            "event-b",
            "run-delivery",
            "run",
            "run-delivery",
            2,
            "two",
            "{}"));

    OutboxRepository outbox = new OutboxRepository(jdbc, transactions);
    List<OutboxRecord> first =
        outbox.claimBatch("publisher-a", 1, Duration.ofMinutes(30));
    List<OutboxRecord> second =
        outbox.claimBatch("publisher-b", 10, Duration.ofMinutes(30));
    assertThat(first).hasSize(1);
    assertThat(second).hasSize(1);
    assertThat(first.getFirst().eventId())
        .isNotEqualTo(second.getFirst().eventId());
    assertThat(outbox.claimBatch("publisher-c", 10, Duration.ofMinutes(30)))
        .isEmpty();

    jdbc.sql(
            """
            UPDATE outbox_event
            SET claimed_at = clock_timestamp() - interval '2 hours'
            WHERE event_id = :eventId
            """)
        .param("eventId", first.getFirst().eventId())
        .update();
    List<OutboxRecord> recovered =
        outbox.claimBatch("publisher-c", 1, Duration.ofMinutes(30));
    assertThat(recovered)
        .extracting(OutboxRecord::eventId)
        .containsExactly(first.getFirst().eventId());
    assertThat(
            outbox.markPublished(
                recovered.getFirst().eventId(), "publisher-c"))
        .isTrue();
    assertThat(
            outbox.markPublished(
                recovered.getFirst().eventId(), "publisher-c"))
        .isFalse();

    InboxRepository inbox = new InboxRepository(jdbc);
    assertThat(inbox.receive("projection-a", "event-a", "run-delivery")).isTrue();
    assertThat(inbox.receive("projection-a", "event-a", "run-delivery")).isFalse();
    assertThat(inbox.receive("projection-b", "event-a", "run-delivery")).isTrue();
    assertThat(inbox.markProcessed("projection-a", "event-a", "{\"ok\":true}"))
        .isTrue();
    assertThat(inbox.markProcessed("projection-a", "event-a", "{\"ok\":true}"))
        .isFalse();
  }

  @Test
  void eventLogIsAppendOnly() {
    RunRepository runs = new RunRepository(jdbc);
    createRun(runs, "run-append");
    new TransactionalEventStore(transactions, new EventLogRepository(jdbc))
        .commit(
            () -> {},
            DomainEvent.create(
                "event-append",
                "run-append",
                "run",
                "run-append",
                1,
                "created",
                "{}"));

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE event_log
                        SET event_type = :eventType
                        WHERE event_id = :eventId
                        """)
                    .param("eventType", "tampered")
                    .param("eventId", "event-append")
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        DELETE FROM event_log
                        WHERE event_id = :eventId
                        """)
                    .param("eventId", "event-append")
                    .update())
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void workingCheckpointNeverAdvancesVerifiedResumePointer() {
    RunRepository runs = new RunRepository(jdbc);
    RunLeaseRepository leases = new RunLeaseRepository(jdbc, transactions);
    createRun(runs, "working-isolation");
    jdbc.sql(
            """
            INSERT INTO strategy (
              run_id, strategy_id, status, payload
            ) VALUES (
              :runId, :strategyId, :status, CAST(:payload AS jsonb)
            )
            """)
        .param("runId", "working-isolation")
        .param("strategyId", "strategy-a")
        .param("status", "active")
        .param("payload", "{}")
        .update();
    jdbc.sql(
            """
            INSERT INTO route (
              run_id, route_id, strategy_id, status, latest_checkpoint_id
            ) VALUES (
              :runId, :routeId, :strategyId, :status, :checkpointId
            )
            """)
        .param("runId", "working-isolation")
        .param("routeId", "route-a")
        .param("strategyId", "strategy-a")
        .param("status", "active")
        .param("checkpointId", "verified-0")
        .update();
    RunLease lease =
        leases.acquire(
            "working-isolation", "explorer-a", Duration.ofMinutes(1));

    CheckpointRepository checkpoints = new CheckpointRepository(jdbc);
    checkpoints.saveWorking(
        "working-isolation",
        "working-1",
        "route-a",
        "strategy-a",
        "verified-0",
        1,
        "pending_review",
        "{\"step\":\"candidate-step\"}",
        lease.ownerId(),
        lease.fencingToken());

    assertThat(
            checkpoints.latestVerifiedCheckpoint(
                "working-isolation", "route-a"))
        .contains("verified-0");
    assertThat(count("working_checkpoint")).isEqualTo(1);
  }

  @Test
  void artifactMetadataAndRunOwnershipCommitToPostgres(@TempDir Path artifactRoot) {
    RunRepository runs = new RunRepository(jdbc);
    createRun(runs, "run-artifact");
    ArtifactStore store =
        new ArtifactStore(
            artifactRoot,
            "run-artifact",
            1024,
            4096,
            new JdbcArtifactMetadataSink(jdbc, transactions));

    String reference =
        store.writeText(
            "verified bytes",
            "text/plain",
            "phase04-test",
            "long-term",
            "proof");

    assertThat(reference).startsWith("artifact://sha256/");
    assertThat(count("artifact")).isEqualTo(1);
    assertThat(count("run_artifact")).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    """
                    SELECT storage_path
                    FROM artifact
                    WHERE content_hash = :contentHash
                    """)
                .param(
                    "contentHash",
                    reference.substring("artifact://sha256/".length()))
                .query(String.class)
                .single())
        .matches("artifacts/sha256/[0-9a-f]{2}/[0-9a-f]{64}");
  }

  private static void createRun(RunRepository runs, String runId) {
    runs.create(runId, HASH, "created", "preflight", "{}");
  }

  private static long count(String table) {
    return switch (table) {
      case "artifact" -> jdbc.sql("SELECT COUNT(*) FROM artifact").query(Long.class).single();
      case "event_log" -> jdbc.sql("SELECT COUNT(*) FROM event_log").query(Long.class).single();
      case "outbox_event" -> jdbc.sql("SELECT COUNT(*) FROM outbox_event").query(Long.class).single();
      case "run_artifact" -> jdbc.sql("SELECT COUNT(*) FROM run_artifact").query(Long.class).single();
      case "working_checkpoint" ->
          jdbc.sql("SELECT COUNT(*) FROM working_checkpoint").query(Long.class).single();
      default -> throw new IllegalArgumentException("unsupported table");
    };
  }

  private static RunLease acquireAfter(
      CountDownLatch start, RunLeaseRepository leases, String owner)
      throws InterruptedException {
    start.await();
    return leases.acquire("run-race", owner, Duration.ofMinutes(1));
  }
}
