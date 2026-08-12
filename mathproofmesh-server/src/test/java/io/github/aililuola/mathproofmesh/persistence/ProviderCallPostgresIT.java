package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.provider.ProviderCallPlan;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRecord;
import io.github.aililuola.mathproofmesh.provider.ProviderCallState;
import io.github.aililuola.mathproofmesh.provider.ProviderCallTransition;
import io.github.aililuola.mathproofmesh.provider.ProviderCircuitSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class ProviderCallPostgresIT {
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
  private static final String HASH = "7".repeat(64);

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(POSTGRES_IMAGE)
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("mathproofmesh")
          .withUsername("mathproofmesh")
          .withPassword("phase07-test-password");

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
    jdbc.sql(
            """
            TRUNCATE TABLE provider_circuit_state, run, artifact CASCADE
            """)
        .update();
  }

  @Test
  void plannedCallTransitionsReloadsReconcilesAndAppliesExactlyOnce() {
    new RunRepository(jdbc)
        .create("run-provider", HASH, "created", "proof", "{}");
    JdbcProviderCallRepository repository =
        new JdbcProviderCallRepository(jdbc, transactions);
    ProviderCallPlan plan =
        new ProviderCallPlan(
            "run-provider",
            "call-one",
            "stable-key",
            "agent-a",
            "deepseek",
            "deepseek-reasoner",
            "route_prove",
            HASH,
            HASH);

    ProviderCallRecord first = repository.plan(plan);
    ProviderCallRecord replay =
        repository.plan(
            new ProviderCallPlan(
                "run-provider",
                "ignored-call-id",
                "stable-key",
                "agent-a",
                "deepseek",
                "deepseek-reasoner",
                "route_prove",
                HASH,
                HASH));
    assertThat(first.callId()).isEqualTo("call-one");
    assertThat(replay.callId()).isEqualTo("call-one");

    repository.transition(
        ProviderCallTransition.state(
            "run-provider",
            "call-one",
            ProviderCallState.PLANNED,
            ProviderCallState.DISPATCHED));
    repository.transition(
        new ProviderCallTransition(
            "run-provider",
            "call-one",
            ProviderCallState.DISPATCHED,
            ProviderCallState.SUCCEEDED,
            100,
            50,
            new BigDecimal("0.0125"),
            321.5d,
            "8".repeat(64),
            "remote-request",
            1,
            BigDecimal.ZERO,
            JsonNodeFactory.instance.objectNode()));

    JdbcProviderCallRepository restarted =
        new JdbcProviderCallRepository(jdbc, transactions);
    ProviderCallRecord stored =
        restarted
            .findByIdempotencyKey("run-provider", "stable-key")
            .orElseThrow();
    assertThat(stored.state()).isEqualTo(ProviderCallState.SUCCEEDED);
    assertThat(stored.inputTokens()).isEqualTo(100);
    assertThat(stored.outputTokens()).isEqualTo(50);
    assertThat(stored.costUsd()).isEqualByComparingTo("0.0125");
    assertThat(stored.requestId()).isEqualTo("remote-request");
    assertThat(stored.retryCount()).isEqualTo(1);
    assertThat(restarted.usageTotals("run-provider").totalTokens())
        .isEqualTo(150);

    assertThat(restarted.markApplied(
            "run-provider", "call-one", "checkpoint:one"))
        .isTrue();
    assertThat(restarted.markApplied(
            "run-provider", "call-one", "checkpoint:one"))
        .isFalse();
    assertThat(restarted.markApplied(
            "run-provider", "call-one", "checkpoint:two"))
        .isFalse();
    assertThat(
            restarted
                .findByIdempotencyKey("run-provider", "stable-key")
                .orElseThrow()
                .appliedAt())
        .isNotNull();
  }

  @Test
  void requestIdentityAndStateMachineAreDatabaseEnforced() {
    new RunRepository(jdbc)
        .create("run-identity", HASH, "created", "proof", "{}");
    JdbcProviderCallRepository repository =
        new JdbcProviderCallRepository(jdbc, transactions);
    repository.plan(
        new ProviderCallPlan(
            "run-identity",
            "call-one",
            "stable-key",
            "agent-a",
            "deepseek",
            "model-a",
            "route_prove",
            HASH,
            HASH));

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE provider_call
                        SET model = 'model-b'
                        WHERE run_id = 'run-identity'
                          AND call_id = 'call-one'
                        """)
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                repository.transition(
                    ProviderCallTransition.state(
                        "run-identity",
                        "call-one",
                        ProviderCallState.DISPATCHED,
                        ProviderCallState.SUCCEEDED)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expected DISPATCHED but was PLANNED");
    assertThatThrownBy(
            () ->
                repository.markApplied(
                    "run-identity", "call-one", "checkpoint"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("only a succeeded");
  }

  @Test
  void circuitSnapshotSurvivesStoreRecreationAndRejectsStaleVersion() {
    Instant failureTime = Instant.parse("2026-01-02T03:04:05Z");
    Instant openUntil = failureTime.plusSeconds(60);
    JdbcCircuitStateStore store = new JdbcCircuitStateStore(jdbc);
    ProviderCircuitSnapshot snapshot =
        new ProviderCircuitSnapshot(
            "deepseek:https://fixture",
            List.of(
                new ProviderCircuitSnapshot.Failure(
                    failureTime, "agent-a", "NETWORK")),
            openUntil,
            3);
    store.save(snapshot);

    ProviderCircuitSnapshot reloaded =
        new JdbcCircuitStateStore(jdbc)
            .load("deepseek:https://fixture")
            .orElseThrow();
    assertThat(reloaded).isEqualTo(snapshot);

    assertThatThrownBy(
            () ->
                store.save(
                    new ProviderCircuitSnapshot(
                        snapshot.providerScope(),
                        List.of(),
                        null,
                        2)))
        .isInstanceOf(OptimisticLockException.class);
    store.delete(snapshot.providerScope());
    assertThat(store.load(snapshot.providerScope())).isEmpty();
  }
}
