package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.FailureLevel;
import io.github.aililuola.mathproofmesh.contract.FinalProof;
import io.github.aililuola.mathproofmesh.contract.Severity;
import io.github.aililuola.mathproofmesh.contract.VerificationIssue;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.contract.VerificationStage;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class FinalProofRevisionPolicy {
  enum DecisionCode {
    REVISE_LOCAL_DEFECT,
    REVIEW_DID_NOT_FAIL,
    PROBLEM_INTEGRITY_FAILED,
    NON_LOCAL_FAILURE,
    TOOL_ACTION_REQUIRED,
    NO_ACTIONABLE_REPAIR,
    WRONG_REVIEW_TARGET
  }

  record Decision(boolean revise, DecisionCode code) {
    Decision {
      Objects.requireNonNull(code, "code");
    }
  }

  Decision assess(VerificationReport report) {
    if (report == null
        || report.verdict() != VerificationVerdict.FAIL) {
      return decision(false, DecisionCode.REVIEW_DID_NOT_FAIL);
    }
    if (!report.problemIntegrityOk()) {
      return decision(false, DecisionCode.PROBLEM_INTEGRITY_FAILED);
    }
    if (!"final-proof".equals(report.targetId())
        || !"final_proof".equals(report.targetType())
        || report.stage() != VerificationStage.STRUCTURAL) {
      return decision(false, DecisionCode.WRONG_REVIEW_TARGET);
    }
    if (report.failureLevel() == FailureLevel.STRATEGY
        || report.issues().stream().anyMatch(issue -> issue.severity() == Severity.CRITICAL)) {
      return decision(false, DecisionCode.NON_LOCAL_FAILURE);
    }
    if (!report.toolRequests().isEmpty()) {
      return decision(false, DecisionCode.TOOL_ACTION_REQUIRED);
    }
    List<VerificationIssue> blockingIssues =
        report.issues().stream()
            .filter(issue -> issue.severity() == Severity.ERROR)
            .toList();
    boolean everyBlockingIssueIsActionable =
        !blockingIssues.isEmpty()
            && blockingIssues.stream().allMatch(FinalProofRevisionPolicy::hasRepairHint);
    if (!everyBlockingIssueIsActionable) {
      return decision(false, DecisionCode.NO_ACTIONABLE_REPAIR);
    }
    return decision(true, DecisionCode.REVISE_LOCAL_DEFECT);
  }

  Map<String, Object> revisionContext(
      Object immutableProblem,
      String problemHash,
      FinalProof candidate,
      VerificationReport structuralReport,
      Object proofGraphSnapshot) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("immutable_problem", Objects.requireNonNull(immutableProblem, "immutableProblem"));
    context.put("problem_hash", requireText(problemHash, "problemHash"));
    context.put("candidate_final_proof", Objects.requireNonNull(candidate, "candidate"));
    context.put(
        "structural_repair_report",
        Objects.requireNonNull(structuralReport, "structuralReport"));
    context.put("repairable_issues", structuralReport.issues());
    context.put("proof_graph", Objects.requireNonNull(proofGraphSnapshot, "proofGraphSnapshot"));
    context.put("authoritative_source_attempt_ids", candidate.sourceAttemptIds());
    context.put(
        "revision_rule",
        "Repair only the enumerated structural defects. Preserve the exact problem, conclusion, "
            + "hypotheses, quantifiers, and admitted source-attempt boundary. Return a complete "
            + "FinalProof; do not claim verification or promote any claim or fact.");
    return Map.copyOf(context);
  }

  FinalProof bindAuthoritative(
      FinalProof revision, FinalProof priorProof, String authoritativeProblemHash) {
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(priorProof, "priorProof");
    return new FinalProof(
        revision.answer(),
        revision.caveats(),
        revision.confidence(),
        revision.dependencies(),
        requireText(authoritativeProblemHash, "authoritativeProblemHash"),
        revision.proofSteps(),
        priorProof.sourceAttemptIds());
  }

  private static boolean hasRepairHint(VerificationIssue issue) {
    return issue.repairHint() != null && !issue.repairHint().isBlank();
  }

  private static Decision decision(boolean revise, DecisionCode code) {
    return new Decision(revise, code);
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }
}
