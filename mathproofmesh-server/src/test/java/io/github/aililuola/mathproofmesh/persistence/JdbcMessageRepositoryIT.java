package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.ArtifactCatalog;
import io.github.aililuola.mathproofmesh.communication.DependencyCatalog;
import io.github.aililuola.mathproofmesh.communication.MessageBroker;
import io.github.aililuola.mathproofmesh.communication.MessageBrokerPolicy;
import io.github.aililuola.mathproofmesh.communication.MessageDeliveryState;
import io.github.aililuola.mathproofmesh.communication.RouteRegistry;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.RouteDescriptor;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.RouteStatus;
import java.util.List;
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
class JdbcMessageRepositoryIT {
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
  private static final String PROBLEM_HASH = "7".repeat(64);

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(POSTGRES_IMAGE)
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("mathproofmesh")
          .withUsername("mathproofmesh")
          .withPassword("phase05-test-password");

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).validateOnMigrate(true).load().migrate();
    jdbc = JdbcClient.create(dataSource);
    transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE run CASCADE").update();
  }

  @Test
  void promptConsumptionAndProviderRequestSurviveRestartWithoutRedelivery() {
    String runId = "phase05-resume";
    createRunAndRoutes(runId);
    RouteRegistry routes = registry();
    JdbcMessageRepository repository =
        new JdbcMessageRepository(runId, jdbc, transactions);
    MessageBroker broker = broker(routes, repository);
    MessageEnvelope fact = fact("restart-fact");

    assertThat(broker.publish(fact, "referee-a", 1).accepted()).isTrue();
    assertThat(
            broker
                .consumeForPrompt("route-b", "provider-request-before-crash", 1, 4)
                .messages())
        .containsExactly(fact);

    JdbcMessageRepository restoredRepository =
        new JdbcMessageRepository(runId, jdbc, transactions);
    MessageBroker restored = broker(routes, restoredRepository);
    assertThat(
            restored
                .consumeForPrompt("route-b", "provider-request-after-crash", 1, 4)
                .messages())
        .isEmpty();
    assertThat(restored.deliveryRecord("restart-fact", "route-b").orElseThrow().state())
        .isEqualTo(MessageDeliveryState.PROMPT_CONSUMED);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*)
                    FROM provider_prompt_request
                    WHERE run_id = :runId
                    """)
                .param("runId", runId)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void duplicatePublishIsIdempotentForMessageDeliveryAndOutbox() {
    String runId = "phase05-dedupe";
    createRunAndRoutes(runId);
    JdbcMessageRepository repository =
        new JdbcMessageRepository(runId, jdbc, transactions);
    MessageBroker broker = broker(registry(), repository);

    broker.publish(fact("original"), "referee-a", 1);
    var duplicate = broker.publish(fact("copy"), "referee-a", 1);

    assertThat(duplicate.duplicateOf()).isEqualTo("original");
    assertThat(repository.snapshot().messages()).hasSize(1);
    assertThat(repository.snapshot().deliveries()).hasSize(1);
    assertThat(repository.snapshot().domainEvents()).hasSize(1);
  }

  @Test
  void acknowledgementAndVerifiedUtilityPersistAsSeparateFacts() {
    String runId = "phase05-receipt";
    createRunAndRoutes(runId);
    JdbcMessageRepository repository =
        new JdbcMessageRepository(runId, jdbc, transactions);
    MessageBroker broker = broker(registry(), repository);
    MessageEnvelope fact = fact("receipt-fact");
    broker.publish(fact, "referee-a", 1);
    broker.consumeForPrompt("route-b", "receipt-provider-request", 1, 4);
    var delivery = broker.deliveryRecord("receipt-fact", "route-b").orElseThrow();
    var receipt =
        broker
            .receiptService()
            .buildReceipt(
                fact,
                delivery,
                ReceiptStatus.ACCEPTED,
                null,
                null,
                null,
                null,
                List.of("verified-step"),
                List.of(),
                "");
    broker.acknowledge(receipt);

    assertThat(repository.findUtility(delivery.deliveryKey())).isEmpty();
    assertThat(
            broker.verifyUtility(
                "receipt-fact",
                "route-b",
                new io.github.aililuola.mathproofmesh.communication.VerifiedDownstreamEffect(
                    java.util.Set.of("verified-step"),
                    java.util.Set.of(),
                    java.util.Set.of(),
                    java.util.Set.of(),
                    java.util.Set.of(),
                    false,
                    2.0,
                    1.0)))
        .isPresent();
    assertThat(repository.findDelivery(delivery.deliveryKey()).orElseThrow().actuallyUsed())
        .isTrue();
  }

  @Test
  void invalidationIsAuditedAndAllowsExactRepublicationAfterRestart() {
    String runId = "phase05-invalidation";
    createRunAndRoutes(runId);
    JdbcMessageRepository repository =
        new JdbcMessageRepository(runId, jdbc, transactions);
    MessageEnvelope fact = fact("reusable");
    MessageBroker broker = broker(registry(), repository);
    broker.publish(fact, "referee-a", 1);

    String reason = "checkpoint_rolled_back:checkpoint-abandoned";
    assertThat(broker.invalidateMessages(List.of(fact.messageId()), reason))
        .singleElement()
        .satisfies(
            invalidated -> {
              assertThat(invalidated.deliveryState()).isEqualTo("invalidated");
              assertThat(invalidated.invalidationReason()).isEqualTo(reason);
            });

    JdbcMessageRepository restored =
        new JdbcMessageRepository(runId, jdbc, transactions);
    assertThat(restored.snapshot().deliveries()).isEmpty();
    assertThat(restored.snapshot().invalidatedDeliveries()).hasSize(1);
    MessageBroker resumed = broker(registry(), restored);
    assertThat(resumed.publish(fact, "referee-a", 2).selectedTargets())
        .containsExactly("route-b");
    assertThat(
            resumed
                .consumeForPrompt("route-b", "republished-request", 2, 4)
                .messages())
        .containsExactly(fact);
  }

  private static MessageBroker broker(
      RouteRegistry routes, JdbcMessageRepository repository) {
    return new MessageBroker(
        MessageBrokerPolicy.strictDefaults(),
        routes,
        ArtifactCatalog.allowRunScopedReferences(),
        new DependencyCatalog() {
          @Override
          public boolean exists(String dependencyId) {
            return true;
          }

          @Override
          public boolean invalidated(String dependencyId) {
            return false;
          }

          @Override
          public boolean wouldCreateCycle(
              String messageId, List<String> dependencyIds) {
            return false;
          }
        },
        repository);
  }

  private static RouteRegistry registry() {
    RouteRegistry routes = new RouteRegistry(PROBLEM_HASH, 2, 8, 0.9);
    routes.register(route("route-a", "strategy-a"));
    routes.register(route("route-b", "strategy-b"));
    routes.assignMember("route-a", "author-a", RouteRole.PROVER, 0);
    routes.assignMember("route-a", "referee-a", RouteRole.REFEREE, 0);
    routes.setNeighbors("route-a", List.of("route-b"));
    routes.setNeighbors("route-b", List.of("route-a"));
    return routes;
  }

  private static RouteDescriptor route(String routeId, String strategyId) {
    return new RouteDescriptor(
        null,
        0,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(strategyId),
        List.of(),
        null,
        List.of(),
        0,
        false,
        null,
        routeId,
        List.of(),
        0,
        RouteStatus.ACTIVE,
        strategyId,
        strategyId);
  }

  private static MessageEnvelope fact(String messageId) {
    return new MessageEnvelope(
        List.of(),
        List.of(),
        "verified identity",
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        messageId,
        MessageType.VERIFIED_LEMMA,
        1.0,
        "verified identity",
        PROBLEM_HASH,
        List.of(),
        null,
        1,
        "1",
        List.of(),
        "author-a",
        RouteRole.PROVER,
        "route-a",
        "verified identity",
        List.of("route-b"),
        2,
        List.of(),
        0.99,
        ClaimStatus.VERIFIED);
  }

  private static void createRunAndRoutes(String runId) {
    new RunRepository(jdbc).create(runId, PROBLEM_HASH, "created", "preflight", "{}");
    for (String suffix : List.of("a", "b")) {
      jdbc.sql(
              """
              INSERT INTO strategy (run_id, strategy_id, status, payload)
              VALUES (:runId, :strategyId, 'active', '{}'::jsonb)
              """)
          .param("runId", runId)
          .param("strategyId", "strategy-" + suffix)
          .update();
      jdbc.sql(
              """
              INSERT INTO route (run_id, route_id, strategy_id, status, payload)
              VALUES (:runId, :routeId, :strategyId, 'active', '{}'::jsonb)
              """)
          .param("runId", runId)
          .param("routeId", "route-" + suffix)
          .param("strategyId", "strategy-" + suffix)
          .update();
    }
  }
}
