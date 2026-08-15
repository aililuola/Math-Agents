package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DesktopContextDistinctFactCollisionTest {
  @Test
  void scopeAndPolarityParticipateInCourtFactIdentity() {
    MessageEnvelope first = fact("fact-D-positive", List.of("D"), "semantic-D-positive", "positive");
    MessageEnvelope second = fact("fact-E-positive", List.of("E"), "semantic-E-positive", "positive");
    MessageEnvelope third = fact("fact-D-negative", List.of("D"), "semantic-D-negative", "negative");
    long collisions =
        java.util.stream.Stream.of(
                first.contentHash().equals(second.contentHash()),
                first.contentHash().equals(third.contentHash()),
                second.contentHash().equals(third.contentHash()))
            .filter(Boolean::booleanValue)
            .count();

    assertThat(first.contentHash()).isNotEqualTo(second.contentHash());
    assertThat(first.contentHash()).isNotEqualTo(third.contentHash());
    assertThat(second.contentHash()).isNotEqualTo(third.contentHash());
    assertThat(collisions).isZero();
    System.out.println("CONTEXT_DISTINCT_FACT_COLLISIONS=" + collisions);
  }

  private static MessageEnvelope fact(
      String id, List<String> scope, String semanticHash, String polarity) {
    return new MessageEnvelope(
        List.of("claim-court://case"),
        List.of("H"),
        "P",
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        id,
        MessageType.VERIFIED_LEMMA,
        1.0d,
        "p",
        "problem-hash",
        List.of(),
        "artifact://proof",
        0,
        "2",
        scope,
        "reviewer",
        RouteRole.PROVER,
        "route-1",
        "P",
        List.of("*"),
        2,
        List.of(),
        1.0d,
        ClaimStatus.VERIFIED,
        "statement-hash",
        semanticHash,
        polarity);
  }
}
