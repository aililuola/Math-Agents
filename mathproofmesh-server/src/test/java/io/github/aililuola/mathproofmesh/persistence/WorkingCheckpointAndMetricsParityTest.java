package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkingCheckpointAndMetricsParityTest {
  @Test
  void metricsDistinguishPublicationDeliveryConsumptionAcceptanceAndUse() {
    PersistenceMetrics.MessageMetrics metrics =
        PersistenceMetrics.messageMetrics(
            List.of(
                new PersistenceMetrics.PublicationDecision("m1", true, null),
                new PersistenceMetrics.PublicationDecision("m1-copy", true, "m1"),
                new PersistenceMetrics.PublicationDecision("m2", true, null)),
            List.of(
                new PersistenceMetrics.DeliveryState("d1", true),
                new PersistenceMetrics.DeliveryState("d2", true),
                new PersistenceMetrics.DeliveryState("d3", false)),
            List.of(
                new PersistenceMetrics.ReceiptState("r1", "accepted"),
                new PersistenceMetrics.ReceiptState("r2", "rejected")),
            List.of(new PersistenceMetrics.UtilityState("u1", "m1")),
            List.of("stored_insight", "stored_insight", "rejected", "route_created"));

    assertThat(metrics.messagePublicationAttempts()).isEqualTo(3);
    assertThat(metrics.messagesPublishedUnique()).isEqualTo(2);
    assertThat(metrics.deliveryRecords()).isEqualTo(3);
    assertThat(metrics.messagesConsumed()).isEqualTo(2);
    assertThat(metrics.messagesSemanticallyAccepted()).isEqualTo(1);
    assertThat(metrics.messagesMathematicallyUsed()).isEqualTo(1);
    assertThat(metrics.inspirationMaterializationActions())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "rejected", 1L,
                "route_created", 1L,
                "stored_insight", 2L));
  }

  @Test
  void factInventoryCountsOnlyBrokerAdmittedIndependentlyReviewedTypedFacts() {
    PersistenceMetrics.FactCandidate admitted =
        new PersistenceMetrics.FactCandidate(
            "admitted", "fact", "verified", "hash-admitted");
    PersistenceMetrics.FactCandidate typedOnly =
        new PersistenceMetrics.FactCandidate(
            "typed-only", "fact", "verified", "hash-typed-only");

    PersistenceMetrics.FactInventory inventory =
        PersistenceMetrics.factInventory(
            List.of(admitted, typedOnly),
            Set.of("admitted"),
            Map.of(
                "admitted",
                new PersistenceMetrics.ReviewProvenance(true, "referee-a")),
            1,
            1);

    assertThat(inventory.factCount()).isEqualTo(1);
    assertThat(inventory.brokerAdmittedGlobalFacts()).containsExactly(admitted);
    assertThat(inventory.typedFactCandidateCount()).isEqualTo(2);
    assertThat(inventory.legacyClaimHistoryCount()).isEqualTo(1);
    assertThat(inventory.legacyVerifiedClaimHistoryCount()).isEqualTo(1);
  }
}
