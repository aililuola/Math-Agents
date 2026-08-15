package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ClaimEvidenceSemanticBinding;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot;
import java.util.List;

final class DesktopClaimEvidenceTestSupport {
  private DesktopClaimEvidenceTestSupport() {}

  static ClaimEvidenceSemanticBinding binding(
      FrozenClaimSnapshot frozen, ObjectNode domains) {
    return new ClaimEvidenceSemanticBinding(
        frozen.problemHash(),
        frozen.claimId(),
        frozen.claimStatementHash(),
        frozen.claimSemanticHash(),
        frozen.statement(),
        frozen.conclusion(),
        frozen.assumptions(),
        frozen.quantifiers(),
        frozen.variableBindings(),
        frozen.scopeLimitations(),
        frozen.polarity(),
        frozen.dependencyClaimIds(),
        domains);
  }

  static ExperimentSpec spec(ClaimEvidenceSemanticBinding binding) {
    return new ExperimentSpec(
        JsonNodeFactory.instance.objectNode(),
        binding.assumptions(),
        false,
        "Use the result only for the exactly bound Claim.",
        "Leave the Claim unresolved.",
        binding.computationDomains(),
        true,
        null,
        "experiment-claim-binding",
        100,
        ComputationMethod.NUMBER_THEORY_CHECK,
        "Continue with a symbolic proof.",
        null,
        "route-claim-binding",
        ComputationPurpose.CHECK_DERIVED_IDENTITY,
        "A deterministic calculation checks the exact bound context.",
        null,
        "server",
        JsonNodeFactory.instance.objectNode(),
        7,
        binding.statement(),
        null,
        "The Claim Court may use only exactly scoped evidence.",
        binding.claimId(),
        binding);
  }

  static ExperimentResult result(
      ExperimentSpec spec, ClaimEvidenceSemanticBinding binding, String targetClaimId) {
    return result(
        spec,
        binding,
        targetClaimId,
        targetClaimId == null ? null : binding);
  }

  static ExperimentResult resultWithoutBinding(
      ExperimentSpec spec, ClaimEvidenceSemanticBinding binding, String targetClaimId) {
    return result(spec, binding, targetClaimId, null);
  }

  private static ExperimentResult result(
      ExperimentSpec spec,
      ClaimEvidenceSemanticBinding binding,
      String targetClaimId,
      ClaimEvidenceSemanticBinding resultBinding) {
    ObjectNode scope = JsonNodeFactory.instance.objectNode();
    scope.set("domains", binding.computationDomains());
    scope.put("complete_domain", true);
    return new ExperimentResult(
        List.of(
            new EvidenceRef(
                "artifact://claim-evidence/result.json",
                "a".repeat(64),
                null,
                "Deterministically replayed Claim evidence.")),
        false,
        10,
        null,
        null,
        null,
        null,
        EvidenceStrength.BOUNDED_EVIDENCE,
        true,
        spec.experimentId(),
        true,
        spec.method(),
        ExperimentOutcome.NOT_REFUTED,
        null,
        spec.pathId(),
        null,
        spec.requestHash(),
        null,
        0.01d,
        scope,
        binding.statement(),
        targetClaimId,
        spec.method().value(),
        "test-tool-v1",
        List.of("deterministically replayed"),
        resultBinding);
  }
}
