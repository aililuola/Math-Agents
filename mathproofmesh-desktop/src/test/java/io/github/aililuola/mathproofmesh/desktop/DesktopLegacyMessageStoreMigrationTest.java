package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.MessageStoreSnapshot;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesktopLegacyMessageStoreMigrationTest {
  @Test
  void migratesOnlyLegacyRecordsWithCompleteTrustedMathematicalSemantics() {
    DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
    MessageEnvelope fact =
        new MessageEnvelope(
            List.of("artifact://legacy-proof"), List.of(), "P(x)", "", null, List.of(),
            List.of(), EvidenceType.NATURAL_PROOF_AUDITED, MemoryTier.FACT, "legacy-claim",
            MessageType.VERIFIED_LEMMA, 1.0d, "p(x)",
            DesktopBrokerArtifactFixture.PROBLEM_HASH, List.of(), "claim-court://legacy", 0, "1",
            List.of("global"), "legacy-author", RouteRole.PROVER, "legacy-source", "P(x)",
            List.of("legacy-target"), 3, List.of(), 1.0d, ClaimStatus.VERIFIED,
            "statement-legacy-claim", "semantic-legacy-claim", "positive");
    MessageEnvelope control = new DesktopBrokerLegacyBlackBoxFixture().genericFailure("legacy-failure");
    MessageStoreSnapshot legacy =
        new MessageStoreSnapshot(
            Map.of(fact.messageId(), fact, control.messageId(), control), Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    int migrated =
        fixture.broker.migrateLegacy(
            legacy,
            DesktopBrokerArtifactFixture.PROBLEM_HASH,
            DesktopBrokerArtifactFixture.ROOT_HASH,
            List.of(fixture.related("legacy-target", "legacy-claim")),
            0);

    assertThat(migrated).isEqualTo(1);
    assertThat(fixture.broker.artifacts())
        .singleElement()
        .satisfies(artifact -> assertThat(artifact.sourceClaimId()).isEqualTo("legacy-claim"));
    assertThat(fixture.broker.deliveries())
        .singleElement()
        .satisfies(delivery -> assertThat(delivery.targetRouteId()).isEqualTo("legacy-target"));
  }
}
