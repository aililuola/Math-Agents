package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.Objects;

/** Distinguishes optional search hints from computation that the proof actually depends on. */
final class RouteComputationEvidencePolicy {
  private RouteComputationEvidencePolicy() {}

  static boolean strategyHasBoundEvidence(StrategyCard strategy) {
    return !Objects.requireNonNull(strategy, "strategy").calculationEvidenceRefs().isEmpty();
  }

  static boolean strategyRequestsTool(StrategyCard strategy) {
    return !Objects.requireNonNull(strategy, "strategy").calculationChecks().isEmpty();
  }

  static boolean attemptHasBoundEvidence(ProofAttempt attempt) {
    return attempt != null
        && attempt.proofSteps().stream()
            .map(ProofStep::calculationEvidenceRefs)
            .anyMatch(refs -> !refs.isEmpty());
  }

  static boolean attemptRequestsTool(ProofAttempt attempt) {
    return attempt != null
        && attempt.proofSteps().stream()
            .map(ProofStep::calculationChecks)
            .anyMatch(checks -> !checks.isEmpty());
  }

  static boolean strategyRequestRemainsRequired(StrategyCard strategy, ProofAttempt attempt) {
    return strategyRequestsTool(strategy) && !isCompleteSelfContainedAttempt(attempt);
  }

  static boolean requiresReplay(
      StrategyCard strategy, ProofAttempt attempt, boolean computationTraceObserved) {
    return computationTraceObserved
        || strategyHasBoundEvidence(strategy)
        || attemptHasBoundEvidence(attempt)
        || attemptRequestsTool(attempt)
        || strategyRequestRemainsRequired(strategy, attempt);
  }

  private static boolean isCompleteSelfContainedAttempt(ProofAttempt attempt) {
    return attempt != null
        && attempt.status() == AttemptStatus.COMPLETE
        && attempt.unresolvedGaps().isEmpty()
        && !attempt.proofSteps().isEmpty()
        && attempt.finalAnswer() != null
        && !attempt.finalAnswer().isBlank()
        && !attemptHasBoundEvidence(attempt)
        && !attemptRequestsTool(attempt);
  }
}
