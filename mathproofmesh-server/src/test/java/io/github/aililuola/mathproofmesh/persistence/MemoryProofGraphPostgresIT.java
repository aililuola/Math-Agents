package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.GraphEdgeType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofGraphEdge;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MemoryProofGraphPostgresIT {
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
  private static final String HASH = "a".repeat(64);

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(POSTGRES_IMAGE)
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("mathproofmesh")
          .withUsername("mathproofmesh")
          .withPassword("phase06-test-password");

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource dataSource =
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
  void clear() {
    jdbc.sql("TRUNCATE TABLE run, artifact CASCADE").update();
  }

  @Test
  void authoritativeStateReloadsIntoJGraphTProjectionInFourBulkQueries() {
    JdbcMemoryProofGraphRepository repository = preparedRepository("projection");

    LoadedProofGraph loaded = repository.loadProofGraph();

    assertThat(loaded.queryCount()).isEqualTo(4);
    assertThat(loaded.graph().obligations()).hasSize(2);
    assertThat(loaded.graph().claimNodes()).hasSize(2);
    assertThat(loaded.graph().dependencyClosure(List.of("dependent-obligation")))
        .containsExactlyInAnyOrder("base-obligation", "dependent-obligation");
    assertThat(loaded.graph().proofDebt("route-a")).isZero();
  }

  @Test
  void counterexampleInvalidationPropagatesWithEventAndOutboxExactlyOnce() {
    JdbcMemoryProofGraphRepository repository = preparedRepository("invalidation");
    MessageEnvelope counterexample = counterexample();

    MemoryInvalidationBatch first =
        repository.invalidateByCounterexample(counterexample, List.of("base"));
    MemoryInvalidationBatch replay =
        repository.invalidateByCounterexample(counterexample, List.of("base"));

    assertThat(first.replay()).isFalse();
    assertThat(first.invalidatedMemoryIds())
        .containsExactly("base", "dependent");
    assertThat(first.reopenedObligationIds())
        .containsExactly("base-obligation", "dependent-obligation");
    assertThat(replay.replay()).isTrue();
    assertThat(replay.invalidatedMemoryIds()).isEqualTo(first.invalidatedMemoryIds());
    assertThat(count("memory_invalidation", "invalidation")).isEqualTo(2);
    assertThat(count("event_log", "invalidation")).isEqualTo(1);
    assertThat(count("outbox_event", "invalidation")).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*)
                    FROM memory_item
                    WHERE run_id = :runId
                      AND memory_id IN ('base', 'dependent')
                      AND memory_tier = 'negative'
                      AND state = 'invalidated'
                      AND version = 1
                    """)
                .param("runId", "invalidation")
                .query(Long.class)
                .single())
        .isEqualTo(2);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*)
                    FROM proof_obligation
                    WHERE run_id = :runId
                      AND status = 'open'
                      AND needs_reverify
                    """)
                .param("runId", "invalidation")
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void crashRollsBackNegativeInvalidationsGraphReopenAndOutboxTogether() {
    JdbcMemoryProofGraphRepository repository = preparedRepository("rollback");

    assertThatThrownBy(
            () ->
                repository.invalidateByCounterexample(
                    counterexample(),
                    List.of("base"),
                    () -> {
                      throw new IllegalStateException("simulated crash");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated crash");

    assertThat(count("memory_invalidation", "rollback")).isZero();
    assertThat(count("event_log", "rollback")).isZero();
    assertThat(count("outbox_event", "rollback")).isZero();
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*)
                    FROM memory_item
                    WHERE run_id = :runId AND memory_tier = 'fact'
                    """)
                .param("runId", "rollback")
                .query(Long.class)
                .single())
        .isEqualTo(2);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*)
                    FROM proof_obligation
                    WHERE run_id = :runId AND status = 'closed'
                    """)
                .param("runId", "rollback")
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void concurrentReplaySerializesOnRunAndMutatesEachRowOnce() throws Exception {
    JdbcMemoryProofGraphRepository repository = preparedRepository("concurrent");
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<MemoryInvalidationBatch>> calls =
        List.of(
            () -> invalidateAfter(start, repository),
            () -> invalidateAfter(start, repository));
    List<MemoryInvalidationBatch> results = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures = calls.stream().map(executor::submit).toList();
      start.countDown();
      for (var future : futures) {
        results.add(future.get());
      }
    }

    assertThat(results).extracting(MemoryInvalidationBatch::replay)
        .containsExactlyInAnyOrder(false, true);
    assertThat(count("memory_invalidation", "concurrent")).isEqualTo(2);
    assertThat(
            jdbc.sql(
                    """
                    SELECT max(version)
                    FROM memory_item
                    WHERE run_id = :runId
                      AND memory_id IN ('base', 'dependent')
                    """)
                .param("runId", "concurrent")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  private static JdbcMemoryProofGraphRepository preparedRepository(String runId) {
    new RunRepository(jdbc).create(runId, HASH, "created", "proof", "{}");
    JdbcMemoryProofGraphRepository repository =
        new JdbcMemoryProofGraphRepository(runId, jdbc, transactions);
    MessageEnvelope base =
        fact("base", "claim p", "route-a", "author-a", List.of());
    MessageEnvelope dependent =
        fact(
            "dependent",
            "claim q",
            "route-a",
            "author-b",
            List.of("base"));
    repository.saveMemory(base);
    repository.saveMemory(dependent);
    ProofObligation baseObligation =
        obligation(
            "base-obligation",
            "claim p",
            List.of(),
            List.of("base"));
    ProofObligation dependentObligation =
        obligation(
            "dependent-obligation",
            "claim q",
            List.of("base-obligation"),
            List.of("dependent"));
    repository.saveObligation(baseObligation, 0, false);
    repository.saveObligation(dependentObligation, 0, false);
    repository.saveEdge(
        new ProofGraphEdge(
            "edge-dependency",
            GraphEdgeType.DEPENDS_ON,
            null,
            "route-a",
            "dependent-obligation",
            "base-obligation"));
    repository.saveEdge(
        new ProofGraphEdge(
            "edge-close-base",
            GraphEdgeType.CLOSES,
            "base",
            "route-a",
            "base",
            "base-obligation"));
    repository.saveEdge(
        new ProofGraphEdge(
            "edge-close-dependent",
            GraphEdgeType.CLOSES,
            "dependent",
            "route-a",
            "dependent",
            "dependent-obligation"));
    repository.saveGraphState(HASH, false, 0);
    return repository;
  }

  private static ProofObligation obligation(
      String id,
      String statement,
      List<String> dependencies,
      List<String> evidence) {
    return new ProofObligation(
        List.of(),
        0.8,
        "",
        dependencies,
        List.of(),
        evidence,
        null,
        ObligationKind.SUBGOAL,
        statement,
        id,
        0.9,
        HASH,
        List.of(),
        List.of("route-a"),
        statement,
        "closed");
  }

  private static MessageEnvelope fact(
      String id,
      String statement,
      String route,
      String author,
      List<String> dependencies) {
    return message(
        id,
        statement,
        statement,
        route,
        author,
        MessageType.VERIFIED_LEMMA,
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        ClaimStatus.VERIFIED,
        dependencies);
  }

  private static MessageEnvelope counterexample() {
    return message(
        "counterexample",
        "claim p",
        "witness refutes claim p",
        "route-c",
        "hunter",
        MessageType.COUNTEREXAMPLE,
        EvidenceType.COUNTEREXAMPLE,
        MemoryTier.NEGATIVE,
        ClaimStatus.REJECTED,
        List.of("base"));
  }

  private static MessageEnvelope message(
      String id,
      String statement,
      String conclusion,
      String route,
      String author,
      MessageType type,
      EvidenceType evidence,
      MemoryTier tier,
      ClaimStatus status,
      List<String> dependencies) {
    return new MessageEnvelope(
        List.of(),
        List.of(),
        conclusion,
        "",
        null,
        dependencies,
        List.of(),
        evidence,
        tier,
        id,
        type,
        1.0,
        statement,
        HASH,
        List.of(),
        null,
        0,
        "1",
        List.of(),
        author,
        RouteRole.PROVER,
        route,
        statement,
        List.of(),
        2,
        List.of(),
        1.0,
        status);
  }

  private static MemoryInvalidationBatch invalidateAfter(
      CountDownLatch start, JdbcMemoryProofGraphRepository repository)
      throws InterruptedException {
    start.await();
    return repository.invalidateByCounterexample(
        counterexample(), List.of("base"));
  }

  private static long count(String table, String runId) {
    String sql =
        switch (table) {
          case "event_log" ->
              "SELECT count(*) FROM event_log WHERE run_id = :runId";
          case "memory_invalidation" ->
              "SELECT count(*) FROM memory_invalidation WHERE run_id = :runId";
          case "outbox_event" ->
              "SELECT count(*) FROM outbox_event WHERE run_id = :runId";
          default -> throw new IllegalArgumentException("unsupported table");
        };
    return jdbc.sql(sql).param("runId", runId).query(Long.class).single();
  }
}
