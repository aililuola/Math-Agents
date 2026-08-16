package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionAction;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionBranch;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionPlan;
import io.github.aililuola.mathproofmesh.contract.ComputationOutcomeApplicationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;
import java.util.Map;

/** Produces a typed action receipt without directly mutating Claim, Fact, or goal authority. */
public final class ComputationOutcomeProjector {
  public ComputationDecisionPlan plan(
      ExperimentSpec spec,
      ComputationExecutionContext context,
      ComputationVerificationReceipt verification) {
    if (context.decisionPlan() != null) {
      return context.decisionPlan();
    }
    String targetClaimId =
        context.claimId().isEmpty() ? spec.targetClaimId() : context.claimId();
    String targetClaimHash = context.claimSemanticHash();
    String obligationId = context.obligationId();
    if ((targetClaimId == null || targetClaimId.isBlank()) && obligationId.isEmpty()) {
      obligationId = "computation-obligation/" + spec.experimentId();
    }
    if (targetClaimId != null && !targetClaimId.isBlank() && targetClaimHash.isEmpty()) {
      targetClaimHash =
          spec.claimEvidenceSemanticBinding() == null
              ? CanonicalJson.stableHash(spec.targetClaim())
              : spec.claimEvidenceSemanticBinding().claimSemanticHash();
    }
    ComputationDecisionAction action = action(verification.authority());
    String scopeHash = verification.verifiedScopeHash();
    return new ComputationDecisionPlan(
        "decision-"
            + CanonicalJson.stableHash(
                Map.of(
                    "request_hash", spec.requestHash(),
                    "verification_hash", verification.receiptHash())),
        targetClaimId,
        targetClaimHash,
        obligationId,
        context.canonicalTargetId(),
        List.of(new ComputationDecisionBranch(specOutcome(spec, verification), action, scopeHash)),
        null);
  }

  public ComputationOutcomeApplicationReceipt project(
      String executionId,
      ExperimentOutcome outcome,
      ComputationDecisionPlan plan,
      ComputationVerificationReceipt verification) {
    ComputationDecisionBranch branch =
        plan.branches().stream()
            .filter(value -> value.outcome() == outcome)
            .findFirst()
            .orElseGet(
                () ->
                    new ComputationDecisionBranch(
                        outcome,
                        ComputationDecisionAction.RETAIN_AUDIT_ONLY,
                        verification.verifiedScopeHash()));
    ComputationDecisionAction allowed = action(verification.authority());
    ComputationDecisionAction applied =
        branch.action() == allowed ? allowed : ComputationDecisionAction.RETAIN_AUDIT_ONLY;
    boolean authoritative =
        verification.valid()
            && applied != ComputationDecisionAction.RETAIN_AUDIT_ONLY
            && applied != ComputationDecisionAction.NO_STATE_CHANGE;
    String applicationId =
        "application-"
            + CanonicalJson.stableHash(
                Map.of(
                    "execution_id", executionId,
                    "plan_hash", plan.planHash(),
                    "verification_hash", verification.receiptHash()));
    return new ComputationOutcomeApplicationReceipt(
        applicationId,
        executionId,
        plan.planHash(),
        verification.receiptHash(),
        applied,
        false,
        authoritative
            ? "Typed action is projection-ready for the existing authority gate."
            : "No mathematical authority mutation is permitted.",
        null);
  }

  private static ComputationDecisionAction action(ComputationVerifiedAuthority authority) {
    return switch (authority) {
      case EXACT_COUNTEREXAMPLE -> ComputationDecisionAction.SUBMIT_EXACT_COUNTEREXAMPLE;
      case FINITE_DOMAIN_CERTIFICATE ->
          ComputationDecisionAction.SATISFY_FINITE_DOMAIN_OBLIGATION;
      case FORMAL_CERTIFICATE -> ComputationDecisionAction.ATTACH_FORMAL_CERTIFICATE;
      case BOUNDED_OBSERVATION -> ComputationDecisionAction.RECORD_BOUNDED_OBSERVATION;
      case AUDIT_ONLY -> ComputationDecisionAction.RETAIN_AUDIT_ONLY;
    };
  }

  private static ExperimentOutcome specOutcome(
      ExperimentSpec spec, ComputationVerificationReceipt verification) {
    return switch (verification.authority()) {
      case EXACT_COUNTEREXAMPLE -> ExperimentOutcome.COUNTEREXAMPLE_FOUND;
      case FINITE_DOMAIN_CERTIFICATE, FORMAL_CERTIFICATE -> ExperimentOutcome.CERTIFIED;
      case BOUNDED_OBSERVATION -> ExperimentOutcome.NOT_REFUTED;
      case AUDIT_ONLY -> ExperimentOutcome.INCONCLUSIVE;
    };
  }
}
