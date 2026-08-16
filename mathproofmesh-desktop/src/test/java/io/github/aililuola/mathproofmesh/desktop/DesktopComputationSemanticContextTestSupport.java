package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.ClaimEvidenceSemanticBinding;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;

final class DesktopComputationSemanticContextTestSupport {
  private static final String LOCAL_ASSUMPTION = "H(x) holds on the declared domain D.";

  private DesktopComputationSemanticContextTestSupport() {}

  static ContextFixture bind(
      DesktopComputationIssue010CoordinatorHarness harness,
      ExperimentSpec source,
      String obligationId)
      throws Exception {
    List<String> assumptions =
        List.of(
            "All values use the declared exact finite input.",
            LOCAL_ASSUMPTION);
    List<QuantifierSpec> quantifiers =
        List.of(
            new QuantifierSpec(
                "x", "declared domain D", "forall", 0, List.of(LOCAL_ASSUMPTION), "x"));
    List<VariableBinding> variableBindings =
        List.of(
            new VariableBinding(
                List.of("x_0"), "x", "declared domain D", obligationId, "x"));
    List<String> dependencies = List.of("external:dependency-claim-context");
    harness.addObligation(obligationId, source.targetClaim(), assumptions, quantifiers);
    ExperimentSpec bound =
        harness.exactBound(source, obligationId, variableBindings, dependencies);
    return new ContextFixture(bound, bound.claimEvidenceSemanticBinding());
  }

  static ExperimentSpec withTargetClaim(ExperimentSpec source, String targetClaim) {
    return copy(source, targetClaim);
  }

  private static ExperimentSpec copy(ExperimentSpec source, String targetClaim) {
    return new ExperimentSpec(
        source.arguments(),
        source.assumptions(),
        source.broadSearch(),
        source.decisionIfConfirmed(),
        source.decisionIfRefuted(),
        source.domains(),
        source.exactArithmetic(),
        null,
        source.experimentId(),
        source.maxCases(),
        source.method(),
        source.noncomputationalAlternative(),
        source.parentCheckpointId(),
        source.pathId(),
        source.purpose(),
        source.reasoningBasis(),
        null,
        source.requestedBy(),
        source.runtimeFingerprint(),
        source.seed(),
        targetClaim,
        source.typedToolGap(),
        source.whyComputationIsNeeded(),
        source.targetClaimId(),
        source.claimEvidenceSemanticBinding());
  }

  static int contextLosses(
      MessageEnvelope message, ClaimEvidenceSemanticBinding binding) {
    int losses = 0;
    losses += message.statement().equals(binding.statement()) ? 0 : 1;
    losses += message.conclusion().equals(binding.conclusion()) ? 0 : 1;
    losses += message.assumptions().equals(binding.assumptions()) ? 0 : 1;
    losses += message.quantifiers().equals(binding.quantifiers()) ? 0 : 1;
    losses += message.variableBindings().equals(binding.variableBindings()) ? 0 : 1;
    losses += message.scopeLimitations().equals(binding.scopeLimitations()) ? 0 : 1;
    losses += message.dependencies().equals(binding.dependencyClaimIds()) ? 0 : 1;
    losses += binding.claimStatementHash().equals(message.claimStatementHash()) ? 0 : 1;
    losses += binding.claimSemanticHash().equals(message.claimSemanticHash()) ? 0 : 1;
    losses += binding.polarity().equals(message.polarity()) ? 0 : 1;
    return losses;
  }

  record ContextFixture(
      ExperimentSpec spec, ClaimEvidenceSemanticBinding binding) {}
}
