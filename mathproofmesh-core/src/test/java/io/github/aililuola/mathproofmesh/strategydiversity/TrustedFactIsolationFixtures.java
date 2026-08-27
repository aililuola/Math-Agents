package io.github.aililuola.mathproofmesh.strategydiversity;

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

final class TrustedFactIsolationFixtures {
  static final String PROBLEM = "fact-context-isolation";
  static final String CLAIM = "The map T is injective.";

  private TrustedFactIsolationFixtures() {}

  static boolean supports(
      CriticalClaimContext claimContext,
      List<String> factAssumptions,
      List<QuantifierSpec> factQuantifiers,
      List<String> factScope,
      List<VariableBinding> factBindings) {
    var claim = StrategyDiversityTestFixtures.claim("required", CLAIM, "required");
    CriticalClaimKeyCompiler compiler = new CriticalClaimKeyCompiler();
    CriticalClaimSemanticKey key = compiler.compile(PROBLEM, claim, claimContext);
    TrustedStrategyPreflightEvidenceSource source =
        new TrustedStrategyPreflightEvidenceSource(
            PROBLEM,
            new NegativeKnowledgeAdmissionGate(new NegativeKnowledgeRegistry()),
            List.of(),
            List.of(
                fact(factAssumptions, factQuantifiers, factScope, factBindings)),
            0);
    return source
        .evaluate(
            key,
            new CriticalClaimPreflightSpec(
                PROBLEM, claim, key, claimContext, "", List.of()))
        .isPresent();
  }

  static QuantifierSpec quantifier(String variable, String kind) {
    return new QuantifierSpec(
        variable, "linear maps", kind, 0, List.of(), variable);
  }

  static VariableBinding binding(String variable, String owner) {
    return new VariableBinding(
        List.of(), variable, "linear maps", owner, variable);
  }

  private static MessageEnvelope fact(
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<String> scope,
      List<VariableBinding> bindings) {
    return new MessageEnvelope(
        List.of(),
        assumptions,
        CLAIM,
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        "context-fact",
        MessageType.VERIFIED_LEMMA,
        1.0d,
        CLAIM,
        PROBLEM,
        quantifiers,
        null,
        0,
        "1",
        scope,
        "trusted-reviewer",
        RouteRole.PROVER,
        "route",
        CLAIM,
        List.of(),
        2,
        bindings,
        1.0d,
        ClaimStatus.VERIFIED);
  }
}
