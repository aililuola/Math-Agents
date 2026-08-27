package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import org.junit.jupiter.api.Test;

class ComputationOutcomeProjectorAuthoritativeContextTest {
  @Test
  void productionContextDoesNotPromoteAModelClaimIdOutsideItsResolvedTargetBinding() {
    ExperimentSpec source = ComputationIssue010TestSupport.finiteMapSpec();
    ExperimentSpec spoofed = withTargetClaimId(source, "model-proposed-claim");
    ComputationExecutionContext context =
        new ComputationExecutionContext(
            "problem-hash",
            "root-goal-hash",
            "route-1",
            "",
            "",
            "isolated-computation-obligation",
            "canonical-isolated-target",
            0,
            null);
    ComputationBroker broker = ComputationFixtures.broker("authoritative-context-projector");
    ComputationBroker.PreparedDecision prepared =
        broker.decide(spoofed, ComputationContext.initial("route-1", 5));
    broker.runExperiment(prepared.spec(), prepared.decision(), null, context);
    var verification =
        broker
            .executionService()
            .lastOutcome(spoofed.experimentId())
            .orElseThrow()
            .verificationReceipt();

    var plan = new ComputationOutcomeProjector().plan(spoofed, context, verification);

    assertThat(plan.targetClaimId()).isBlank();
    assertThat(plan.targetClaimSemanticHash()).isBlank();
    assertThat(plan.targetObligationId()).isEqualTo("isolated-computation-obligation");
  }

  private static ExperimentSpec withTargetClaimId(ExperimentSpec source, String claimId) {
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
        source.targetClaim(),
        source.typedToolGap(),
        source.whyComputationIsNeeded(),
        claimId,
        null);
  }
}
