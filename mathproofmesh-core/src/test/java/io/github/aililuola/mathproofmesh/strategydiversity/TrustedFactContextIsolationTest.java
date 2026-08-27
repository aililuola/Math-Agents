package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeAdmissionGate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedFactContextIsolationTest {
  private static final String PROBLEM = "context-isolation";
  private static final String CLAIM = "The map T is injective.";

  @Test
  void conditionalOrDifferentlyQuantifiedFactsCannotSupportAnUnconditionalClaim() {
    var claim = StrategyDiversityTestFixtures.claim("required", CLAIM, "required");
    CriticalClaimSemanticKey unconditional =
        new CriticalClaimKeyCompiler()
            .compile(PROBLEM, claim, List.of(), List.of(), List.of(), "positive");
    TrustedStrategyPreflightEvidenceSource source =
        new TrustedStrategyPreflightEvidenceSource(
            PROBLEM,
            new NegativeKnowledgeAdmissionGate(new NegativeKnowledgeRegistry()),
            List.of(),
            List.of(conditionalFact()),
            0);
    CriticalClaimPreflightSpec spec =
        new CriticalClaimPreflightSpec(PROBLEM, claim, unconditional, "", List.of());

    assertThat(source.evaluate(unconditional, spec)).isEmpty();
  }

  private static MessageEnvelope conditionalFact() {
    QuantifierSpec quantifier =
        new QuantifierSpec("T", "linear maps", "forall", 0, List.of("ker(T)={0}"), "T");
    VariableBinding binding =
        new VariableBinding(List.of(), "T", "linear maps", "fact", "T");
    return new MessageEnvelope(
        List.of(),
        List.of("ker(T)={0}"),
        CLAIM,
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        "conditional-fact",
        MessageType.VERIFIED_LEMMA,
        1.0d,
        CLAIM,
        PROBLEM,
        List.of(quantifier),
        null,
        0,
        "1",
        List.of("under ker(T)={0}"),
        "trusted-reviewer",
        RouteRole.PROVER,
        "route",
        CLAIM,
        List.of(),
        2,
        List.of(binding),
        1.0d,
        ClaimStatus.VERIFIED);
  }
}
