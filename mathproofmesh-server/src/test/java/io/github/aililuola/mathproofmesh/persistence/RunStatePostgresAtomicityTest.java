package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.runstate.RunExecutionStatus;
import io.github.aililuola.mathproofmesh.runstate.RunStateServerTestSupport;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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
final class RunStatePostgresAtomicityTest {
  private static final String IMAGE =
      "postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("mathproofmesh")
          .withUsername("mathproofmesh")
          .withPassword("run-state-test");

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @Test
  void snapshotTransitionLegacyProjectionAndOutboxCommitUnderOneFence() {
    String runId = "postgres-run-state";
    new RunRepository(jdbc).create(runId, "1".repeat(64), "created", "proof", "{}");
    long token =
        new RunLeaseRepository(jdbc, transactions)
            .acquire(runId, "worker", Duration.ofMinutes(1))
            .fencingToken();
    JdbcRunStateStore store = new JdbcRunStateStore(jdbc, transactions);
    var state = RunStateServerTestSupport.state(runId, RunExecutionStatus.FAILED, true);
    store.compareAndSet(runId, -1, state, "worker", token);
    assertThat(store.load(runId)).contains(state);
    assertThat(store.transitions(runId)).hasSize(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE run_id = :runId")
            .param("runId", runId).query(Long.class).single())
        .isEqualTo(1L);
    assertThat(new RunRepository(jdbc).require(runId).status()).isEqualTo("interrupted");
    assertThatThrownBy(() -> store.compareAndSet(runId, -1, state, "worker", token))
        .isInstanceOf(OptimisticLockException.class);
  }
}
