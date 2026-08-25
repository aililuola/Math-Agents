package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimEvidenceSemanticBinding;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopUntrustedComputationClaimBindingIsolationTest {
  private static final String MODEL_CLAIM_ID = "model-proposed-claim";

  @TempDir Path temporaryDirectory;

  @Test
  void incompleteModelClaimBindingIsDowngradedToAnIsolatedComputationQuestion()
      throws Exception {
    int executionContextFailures = 0;
    int modelClaimAuthorityBindings;
    int modelClaimAuthorityProjections;
    int isolatedQuestionProjections;
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "untrusted-computation-claim-binding")) {
      harness.initializeRoute();
      int factsBefore = harness.typedMemory().facts().size();
      ExperimentSpec spec = incompleteModelBinding(
          DesktopComputationIssue010Support.finiteMap("untrusted-binding"));

      DesktopSolveCheckpoint.ComputationCheckpoint trace;
      try {
        trace = harness.runComputation(spec);
      } catch (IllegalArgumentException failure) {
        executionContextFailures++;
        throw failure;
      }

      var execution = harness.execution(spec.experimentId());
      modelClaimAuthorityBindings =
          trace.targetBinding().isolatedComputationQuestion()
                  && trace.targetBinding().claimId().isEmpty()
                  && trace.targetBinding().claimSemanticHash().isEmpty()
                  && execution.claimId().isEmpty()
              ? 0
              : 1;
      modelClaimAuthorityProjections =
          harness.typedMemory().facts().size() == factsBefore
                  && harness.proofGraph().claimNodes().stream()
                      .noneMatch(
                          claim ->
                              MODEL_CLAIM_ID.equals(claim.messageId())
                                  || MODEL_CLAIM_ID.equals(claim.rawSourceRef())
                                  || claim.claimSemanticHash() != null)
              ? 0
              : 1;
      isolatedQuestionProjections = execution.authorityProjections();

      assertThat(executionContextFailures).isZero();
      assertThat(modelClaimAuthorityBindings).isZero();
      assertThat(modelClaimAuthorityProjections).isZero();
      assertThat(isolatedQuestionProjections).isEqualTo(1);
      assertThat(harness.obligation(trace.targetBinding().obligationId()).status())
          .isEqualTo("closed");
    }

    System.out.println("UNTRUSTED_BINDING_EXECUTION_CONTEXT_FAILURES=" + executionContextFailures);
    System.out.println("UNTRUSTED_MODEL_CLAIM_AUTHORITY_BINDINGS=" + modelClaimAuthorityBindings);
    System.out.println("UNTRUSTED_MODEL_CLAIM_AUTHORITY_PROJECTIONS=" + modelClaimAuthorityProjections);
    System.out.println("ISOLATED_COMPUTATION_QUESTION_PROJECTIONS=" + isolatedQuestionProjections);
    System.out.println("RESULT=PASS");
  }

  private static ExperimentSpec incompleteModelBinding(ExperimentSpec source) {
    String claimId = MODEL_CLAIM_ID;
    ClaimEvidenceSemanticBinding binding =
        new ClaimEvidenceSemanticBinding(
            DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
            claimId,
            "",
            "",
            source.targetClaim(),
            source.targetClaim(),
            source.assumptions(),
            List.of(),
            List.of(),
            List.of(),
            "positive",
            List.of(),
            source.domains());
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
        binding);
  }
}
