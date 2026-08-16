package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class NegativeKnowledgePolaritySemanticKeyTest {
  private static final String PROBLEM_HASH = "p".repeat(64);
  private static final String STATEMENT = "P(x)";
  private static final List<String> ASSUMPTIONS = List.of("x belongs to D");
  private static final List<QuantifierSpec> QUANTIFIERS =
      List.of(new QuantifierSpec("x", "D", "forall", 0, List.of(), "x"));
  private static final List<VariableBinding> BINDINGS =
      List.of(new VariableBinding(List.of(), "x", "D", "claim-polarity", "x"));
  private static final List<String> SCOPE = List.of("all x in D");

  @Test
  void sameStatementWithOppositePolarityHasDistinctPermanentIdentity() {
    MessageEnvelope positive = counterexample("positive");
    MessageEnvelope negative = counterexample("negative");
    NegativeKnowledgeCandidate positiveCandidate =
        NegativeKnowledgeRegistry.candidateFromMessage(
            positive,
            NegativeKnowledgeSurface.STRATEGY_ADMISSION,
            NegativeCandidateIntent.POSITIVE_DEPENDENCY);
    NegativeKnowledgeCandidate negativeCandidate =
        NegativeKnowledgeRegistry.candidateFromMessage(
            negative,
            NegativeKnowledgeSurface.STRATEGY_ADMISSION,
            NegativeCandidateIntent.POSITIVE_DEPENDENCY);

    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    registry.registerVerifiedCounterexample(positive, authority(positive));
    NegativeKnowledgeDecision positiveDecision = registry.decide(positiveCandidate, 0);
    NegativeKnowledgeDecision negativeDecision = registry.decide(negativeCandidate, 0);
    registry.registerVerifiedCounterexample(negative, authority(negative));

    int positiveExactBlocks =
        positiveDecision.code() == NegativeKnowledgeDecisionCode.BLOCK_PERMANENT ? 1 : 0;
    int negativePolarityFalseBlocks =
        negativeDecision.code() == NegativeKnowledgeDecisionCode.BLOCK_PERMANENT ? 1 : 0;
    int collisions = Math.max(0, 2 - registry.records().size());

    assertThat(positiveCandidate.semanticKey()).isNotEqualTo(negativeCandidate.semanticKey());
    assertThat(positiveExactBlocks).isOne();
    assertThat(negativePolarityFalseBlocks).isZero();
    assertThat(collisions).isZero();
    System.out.println("POSITIVE_EXACT_REENTRY_BLOCKS=" + positiveExactBlocks);
    System.out.println("NEGATIVE_POLARITY_FALSE_BLOCKS=" + negativePolarityFalseBlocks);
    System.out.println("SAME_STATEMENT_POLARITY_COLLISIONS=" + collisions);
  }

  private static MessageEnvelope counterexample(String polarity) {
    String id = "polarity-counterexample-" + polarity;
    String artifact = "experiment://" + id;
    String statementHash = CanonicalJson.stableHash(STATEMENT);
    String semanticHash =
        CanonicalJson.stableHash(
            Map.of(
                "statement", STATEMENT,
                "assumptions", ASSUMPTIONS,
                "quantifiers", QUANTIFIERS,
                "bindings", BINDINGS,
                "scope", SCOPE,
                "polarity", polarity));
    return new MessageEnvelope(
        List.of(artifact),
        ASSUMPTIONS,
        "A replayed witness refutes the bound claim.",
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.COUNTEREXAMPLE,
        MemoryTier.NEGATIVE,
        id,
        MessageType.COUNTEREXAMPLE,
        1.0d,
        STATEMENT,
        PROBLEM_HASH,
        QUANTIFIERS,
        "result-" + id,
        0,
        "1",
        SCOPE,
        "independent-computation-replay",
        RouteRole.SKEPTIC,
        "route-polarity",
        STATEMENT,
        List.of(),
        2,
        BINDINGS,
        1.0d,
        ClaimStatus.REJECTED,
        statementHash,
        semanticHash,
        polarity);
  }

  private static VerifiedCounterexampleAuthority authority(MessageEnvelope message) {
    return VerifiedCounterexampleAuthority.independentReplay(
        true,
        true,
        ComputationEvidenceGate.EvidenceAuthority.REFUTED,
        message.artifactRefs().getFirst(),
        message.statement(),
        message.rawSourceRef(),
        List.of());
  }
}
