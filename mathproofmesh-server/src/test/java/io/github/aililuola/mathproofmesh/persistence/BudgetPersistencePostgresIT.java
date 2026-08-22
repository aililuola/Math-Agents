package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.orchestration.BudgetResourceVector;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
final class BudgetPersistencePostgresIT {
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);
  private static final String HASH_C = "c".repeat(64);
  private static final String HASH_D = "d".repeat(64);

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("mathproofmesh")
          .withUsername("mathproofmesh")
          .withPassword("issue-013-budget-test");

  private static JdbcClient jdbc;
  private static BudgetPersistenceRepository repository;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
    jdbc = JdbcClient.create(dataSource);
    repository =
        new BudgetPersistenceRepository(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE run CASCADE").update();
    jdbc.sql(
            """
            INSERT INTO run (
              run_id, problem_hash, status, current_stage, config_payload,
              fencing_token, version
            ) VALUES (
              'budget-run', :hash, 'running', 'scheduler', '{}'::jsonb, 0, 0
            )
            """)
        .param("hash", HASH_A)
        .update();
    jdbc.sql(
            """
            INSERT INTO pricing_snapshot (
              run_id, pricing_hash, provider, model, input_per_million,
              output_per_million, billing_mode, config_hash, content_hash,
              version, fencing_token
            ) VALUES (
              'budget-run', :pricingHash, 'mock', 'model', 0, 0,
              'BILLING_EXEMPT', :configHash, :contentHash, 0, 0
            )
            """)
        .param("pricingHash", HASH_B)
        .param("configHash", HASH_C)
        .param("contentHash", HASH_D)
        .update();
    jdbc.sql(
            """
            INSERT INTO budget_decision (
              run_id, epoch_id, decision_id, budget_state_hash, selected_action,
              status, calls, estimated_input_tokens, max_output_tokens,
              max_total_tokens, max_cost_usd, bucket, payload, content_hash,
              version, fencing_token
            ) VALUES (
              'budget-run', 'epoch-1', 'decision-1', :stateHash, 'DEEPEN',
              'ADMITTED', 10, 400, 600, 1000, 10, 'DEPTH', '{}'::jsonb,
              :contentHash, 0, 0
            )
            """)
        .param("stateHash", HASH_C)
        .param("contentHash", HASH_A)
        .update();
  }

  @Test
  void concurrentAdmissionFencingForeignKeysAndSettlementAreFailClosed() throws Exception {
    BudgetResourceVector requested = vector(6, 50, 100, "1");
    BudgetResourceVector limit = vector(10, 400, 600, "10");
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> racers =
        List.of(
            () -> reserveAfter(start, "envelope-a", HASH_A, requested, limit),
            () -> reserveAfter(start, "envelope-b", HASH_B, requested, limit));
    int admissions = 0;
    int rejections = 0;
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures = racers.stream().map(executor::submit).toList();
      start.countDown();
      for (var future : futures) {
        try {
          if (future.get()) {
            admissions++;
          }
        } catch (java.util.concurrent.ExecutionException failure) {
          assertThat(failure.getCause())
              .isInstanceOf(IllegalStateException.class)
              .hasMessage("ACTION_BUDGET_ENVELOPE_EXHAUSTED");
          rejections++;
        }
      }
    }
    assertThat(admissions).isEqualTo(1);
    assertThat(rejections).isEqualTo(1);
    assertThat(count("budget_envelope")).isEqualTo(1L);

    String envelopeId =
        jdbc.sql("SELECT envelope_id FROM budget_envelope WHERE run_id = 'budget-run'")
            .query(String.class)
            .single();
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE budget_envelope
                        SET status = 'ACTIVE', version = 0, fencing_token = 0
                        WHERE run_id = 'budget-run' AND envelope_id = :envelopeId
                        """)
                    .param("envelopeId", envelopeId)
                    .update())
        .isInstanceOf(DataAccessException.class);

    insertProviderCall("call-1", "provider-key-1");
    insertReservation(envelopeId, "reservation-1", "call-1", "physical-key-1");
    assertThatThrownBy(
            () -> insertReservation(envelopeId, "reservation-2", "call-1", "physical-key-1"))
        .isInstanceOf(DataAccessException.class);
    insertUsage(envelopeId, "usage-1", "reservation-1", "call-1");
    assertThatThrownBy(
            () -> insertUsage(envelopeId, "usage-2", "reservation-1", "call-1"))
        .isInstanceOf(DataAccessException.class);
    assertThat(count("budget_usage_event")).isEqualTo(1L);

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO budget_reservation (
                          run_id, epoch_id, envelope_id, reservation_id, provider_call_id,
                          idempotency_key, status, calls, estimated_input_tokens,
                          max_output_tokens, max_total_tokens, max_cost_usd, pricing_hash,
                          payload, content_hash, version, fencing_token
                        ) VALUES (
                          'budget-run', 'epoch-1', :envelopeId, 'bad-fk', 'missing-call',
                          'bad-key', 'RESERVED', 1, 10, 20, 30, 0, :pricingHash,
                          '{}'::jsonb, :contentHash, 0, 0
                        )
                        """)
                    .param("envelopeId", envelopeId)
                    .param("pricingHash", HASH_B)
                    .param("contentHash", HASH_D)
                    .update())
        .isInstanceOf(DataAccessException.class);

    assertThat(
            jdbc.sql(
                    "SELECT status FROM budget_reservation WHERE reservation_id = 'reservation-1'")
                .query(String.class)
                .single())
        .isEqualTo("SETTLED");
  }

  private static boolean reserveAfter(
      CountDownLatch start,
      String envelopeId,
      String contentHash,
      BudgetResourceVector requested,
      BudgetResourceVector limit)
      throws InterruptedException {
    start.await();
    return repository.reserveEnvelope(
        "budget-run",
        "epoch-1",
        "decision-1",
        envelopeId,
        "work-" + envelopeId,
        "DEPTH",
        requested,
        limit,
        contentHash,
        0L,
        0L);
  }

  private static void insertProviderCall(String callId, String idempotencyKey) {
    jdbc.sql(
            """
            INSERT INTO provider_call (
              run_id, call_id, idempotency_key, agent_id, provider,
              request_hash, state, payload
            ) VALUES (
              'budget-run', :callId, :idempotencyKey, 'agent', 'mock',
              :requestHash, 'succeeded', '{}'::jsonb
            )
            """)
        .param("callId", callId)
        .param("idempotencyKey", idempotencyKey)
        .param("requestHash", HASH_A)
        .update();
  }

  private static void insertReservation(
      String envelopeId, String reservationId, String callId, String idempotencyKey) {
    jdbc.sql(
            """
            INSERT INTO budget_reservation (
              run_id, epoch_id, envelope_id, reservation_id, provider_call_id,
              idempotency_key, status, calls, estimated_input_tokens,
              max_output_tokens, max_total_tokens, max_cost_usd, pricing_hash,
              payload, content_hash, version, fencing_token
            ) VALUES (
              'budget-run', 'epoch-1', :envelopeId, :reservationId, :callId,
              :idempotencyKey, 'SETTLED', 1, 10, 20, 30, 0, :pricingHash,
              '{}'::jsonb, :contentHash, 0, 0
            )
            """)
        .param("envelopeId", envelopeId)
        .param("reservationId", reservationId)
        .param("callId", callId)
        .param("idempotencyKey", idempotencyKey)
        .param("pricingHash", HASH_B)
        .param("contentHash", HASH_C)
        .update();
  }

  private static void insertUsage(
      String envelopeId, String usageId, String reservationId, String callId) {
    jdbc.sql(
            """
            INSERT INTO budget_usage_event (
              run_id, epoch_id, usage_event_id, envelope_id, reservation_id,
              provider_call_id, status, actual_calls, actual_input_tokens,
              actual_output_tokens, actual_total_tokens, actual_cost_usd,
              bucket, payload, content_hash, version, fencing_token
            ) VALUES (
              'budget-run', 'epoch-1', :usageId, :envelopeId, :reservationId,
              :callId, 'SETTLED', 1, 8, 16, 24, 0,
              'DEPTH', '{}'::jsonb, :contentHash, 0, 0
            )
            """)
        .param("usageId", usageId)
        .param("envelopeId", envelopeId)
        .param("reservationId", reservationId)
        .param("callId", callId)
        .param("contentHash", HASH_D)
        .update();
  }

  private static BudgetResourceVector vector(
      long calls, long input, long output, String cost) {
    return new BudgetResourceVector(
        calls, input, output, Math.addExact(input, output), new BigDecimal(cost));
  }

  private static long count(String table) {
    return switch (table) {
      case "budget_envelope" ->
          jdbc.sql("SELECT COUNT(*) FROM budget_envelope").query(Long.class).single();
      case "budget_usage_event" ->
          jdbc.sql("SELECT COUNT(*) FROM budget_usage_event").query(Long.class).single();
      default -> throw new IllegalArgumentException("unsupported table");
    };
  }
}
