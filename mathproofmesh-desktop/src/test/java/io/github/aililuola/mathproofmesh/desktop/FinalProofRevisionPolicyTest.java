package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.contract.FailureLevel;
import io.github.aililuola.mathproofmesh.contract.FinalProof;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.Severity;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationIssue;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.contract.VerificationStage;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

final class FinalProofRevisionPolicyTest {
  private final FinalProofRevisionPolicy policy = new FinalProofRevisionPolicy();

  @Test
  void admitsOnlyActionableLocalStructuralFailures() {
    FinalProofRevisionPolicy.Decision decision =
        policy.assess(report(true, FailureLevel.PLAN, Severity.ERROR, "Cite s7."));

    assertTrue(decision.revise());
    assertEquals(
        FinalProofRevisionPolicy.DecisionCode.REVISE_LOCAL_DEFECT, decision.code());
  }

  @Test
  void refusesRepairWhenTheProblemIdentityWasNotPreserved() {
    FinalProofRevisionPolicy.Decision decision =
        policy.assess(report(false, FailureLevel.PLAN, Severity.ERROR, "Cite s7."));

    assertFalse(decision.revise());
    assertEquals(
        FinalProofRevisionPolicy.DecisionCode.PROBLEM_INTEGRITY_FAILED, decision.code());
  }

  @Test
  void refusesStrategyFailuresCriticalDefectsAndMissingRepairHints() {
    assertEquals(
        FinalProofRevisionPolicy.DecisionCode.NON_LOCAL_FAILURE,
        policy.assess(report(true, FailureLevel.STRATEGY, Severity.ERROR, "Replace proof."))
            .code());
    assertEquals(
        FinalProofRevisionPolicy.DecisionCode.NON_LOCAL_FAILURE,
        policy.assess(report(true, FailureLevel.PLAN, Severity.CRITICAL, "Replace theorem."))
            .code());
    assertEquals(
        FinalProofRevisionPolicy.DecisionCode.NO_ACTIONABLE_REPAIR,
        policy.assess(report(true, FailureLevel.PLAN, Severity.ERROR, null)).code());
  }

  @Test
  void serverRebindsProblemAndSourceAuthorityAfterRevision() {
    FinalProof prior = proof("authoritative-hash", List.of("attempt-verified"));
    FinalProof modelRevision = proof("forged-hash", List.of("attempt-forged"));

    FinalProof rebound = policy.bindAuthoritative(modelRevision, prior, "authoritative-hash");

    assertEquals("authoritative-hash", rebound.problemHash());
    assertEquals(List.of("attempt-verified"), rebound.sourceAttemptIds());
  }

  private static VerificationReport report(
      boolean problemIntegrity,
      FailureLevel failureLevel,
      Severity severity,
      String repairHint) {
    VerificationIssue issue =
        new VerificationIssue(
            null,
            "The boundary case is closed.",
            null,
            "The boundary case is omitted.",
            "BOUNDARY_CASE_OMITTED",
            "issue-boundary",
            "structural",
            "The endpoint orientation is already established.",
            repairHint,
            severity,
            "s9");
    return new VerificationReport(
        "reviewer",
        List.of("s7", "s9"),
        "A localized structural defect was found.",
        0.99d,
        failureLevel,
        "s9",
        List.of(issue),
        problemIntegrity,
        null,
        "review-local-boundary",
        VerificationStage.STRUCTURAL,
        List.of(),
        "final-proof",
        "final_proof",
        List.of(),
        List.of(),
        new UsageRecord(),
        VerificationVerdict.FAIL);
  }

  private static FinalProof proof(String problemHash, List<String> sourceAttemptIds) {
    ProofStep step =
        new ProofStep(
            "main",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0.99d,
            List.of("s7"),
            List.of(),
            true,
            "The endpoint case cites s7.",
            "The insertion edge is valid.",
            "s9",
            "derivation");
    return new FinalProof(
        "The tournament has a directed Hamilton path.",
        List.of(),
        0.99d,
        List.of(),
        problemHash,
        List.of(step),
        sourceAttemptIds);
  }
}
