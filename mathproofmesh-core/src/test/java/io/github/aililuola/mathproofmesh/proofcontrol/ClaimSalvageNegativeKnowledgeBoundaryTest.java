package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.memory.MemoryPolicy;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimSalvageNegativeKnowledgeBoundaryTest {

  @Test
  void ordinaryClaimSalvageDoesNotCreateOrUpgradeNegativeKnowledge() {
    TypedMemory memory = new TypedMemory(MemoryPolicy.defaults());
    String beforeHash = memory.negativeKnowledgeRegistry().registryHash();
    long beforePermanent =
        memory.negativeKnowledgeRegistry().records().stream().filter(record -> record.permanent()).count();
    var claim = AttemptArtifactFixtures.claim("local", "A harmless local identity.", List.of());
    AttemptArtifactLedger ledger = AttemptArtifactFixtures.ledger(AttemptStatus.FAILED, claim);
    AttemptArtifactRecord verified =
        ledger.applyReviewBatch(
                AttemptArtifactFixtures.batch(
                    "review", AttemptArtifactFixtures.decision("local", VerificationVerdict.PASS, false)),
                0.8d)
            .getFirst();
    MessageEnvelope fact = fact(claim.statement());

    memory.addFact(fact, "reviewer", 0);
    ledger.markPromoted(verified.artifactId(), fact.messageId());

    assertThat(memory.negativeKnowledgeRegistry().registryHash()).isEqualTo(beforeHash);
    assertThat(memory.negativeKnowledgeRegistry().records().stream().filter(record -> record.permanent()).count())
        .isEqualTo(beforePermanent);
  }

  private static MessageEnvelope fact(String statement) {
    return new MessageEnvelope(
        List.of("artifact://review"), List.of(), statement, "", null, List.of(), List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED, MemoryTier.FACT, "fact-local",
        MessageType.VERIFIED_LEMMA, 1.0d, statement, AttemptArtifactFixtures.PROBLEM_HASH,
        List.of(), "artifact://review", 0, "1", List.of(), "author", RouteRole.PROVER,
        "route-a", statement, List.of(), 2, List.of(), 0.95d, ClaimStatus.VERIFIED);
  }
}
