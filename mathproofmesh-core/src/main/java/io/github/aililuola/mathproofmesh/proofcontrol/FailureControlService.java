package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Separates execution, bridge, plan, and framing failures. */
public final class FailureControlService {
  public record Failure(
      String id,
      String routeId,
      String targetId,
      ProofControlModels.FailureClass failureClass,
      String firstErrorFingerprint,
      List<String> evidence,
      String recommendedAction,
      double confidence) {
    public Failure {
      evidence = List.copyOf(evidence);
      ProofControlModels.unit(confidence, "confidence");
    }
  }

  public record RewriteRequest(
      String id,
      String routeId,
      String failureId,
      List<String> preservedFactIds,
      List<String> preservedStepIds,
      List<String> invalidatedPlanElements,
      List<String> proposedWeakerTargets,
      List<String> bridgeObligationIds,
      boolean representationChangeRequired,
      String status) {
    public RewriteRequest {
      preservedFactIds = List.copyOf(preservedFactIds);
      preservedStepIds = List.copyOf(preservedStepIds);
      invalidatedPlanElements = List.copyOf(invalidatedPlanElements);
      proposedWeakerTargets = List.copyOf(proposedWeakerTargets);
      bridgeObligationIds = List.copyOf(bridgeObligationIds);
    }
  }

  public Failure classify(
      String routeId,
      String targetId,
      String reason,
      String firstError,
      List<String> evidence) {
    String normalized =
        ProofIdentity.normalizeText(reason).toLowerCase(Locale.ROOT);
    ProofControlModels.FailureClass failureClass;
    String action;
    double confidence;
    if (contains(normalized, "timeout", "provider", "rate limit", "budget", "transport")) {
      failureClass = ProofControlModels.FailureClass.EXECUTION;
      action = "retry_or_failover";
      confidence = 0.95d;
    } else if (contains(normalized, "wrong goal", "scope", "quantifier", "overstrong")) {
      failureClass = ProofControlModels.FailureClass.FRAMING;
      action = "scope_goal_rewrite";
      confidence = 0.95d;
    } else if (contains(normalized, "plan exhausted", "stagnation", "no executable step")) {
      failureClass = ProofControlModels.FailureClass.PLAN;
      action = "rewrite_blueprint";
      confidence = 0.9d;
    } else {
      failureClass = ProofControlModels.FailureClass.BRIDGE;
      action = "create_minimal_bridge";
      confidence = 0.8d;
    }
    String fingerprint =
        firstError == null || firstError.isBlank()
            ? null
            : CanonicalJson.stableHash(
                    ProofIdentity.obligationIdentityText(firstError))
                .substring(0, 20);
    String id =
        "failure_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "route", routeId,
                        "target", targetId,
                        "class", failureClass.name(),
                        "fingerprint", fingerprint == null ? "" : fingerprint))
                .substring(0, 20);
    return new Failure(
        id,
        routeId,
        targetId,
        failureClass,
        fingerprint,
        evidence == null ? List.of() : evidence,
        action,
        confidence);
  }

  public RewriteRequest rewrite(
      Failure failure,
      List<String> verifiedFactIds,
      List<String> verifiedStepIds,
      List<String> invalidPlanElements,
      List<String> weakerTargets,
      List<String> bridgeIds) {
    boolean representationChange =
        failure.failureClass() == ProofControlModels.FailureClass.FRAMING
            || failure.failureClass() == ProofControlModels.FailureClass.PLAN;
    String id =
        "blueprint_rewrite_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "failure", failure.id(),
                        "facts", sorted(verifiedFactIds),
                        "steps", sorted(verifiedStepIds),
                        "weaker", sorted(weakerTargets),
                        "bridges", sorted(bridgeIds)))
                .substring(0, 20);
    return new RewriteRequest(
        id,
        failure.routeId(),
        failure.id(),
        sorted(verifiedFactIds),
        sorted(verifiedStepIds),
        sorted(invalidPlanElements),
        sorted(weakerTargets),
        sorted(bridgeIds),
        representationChange,
        "pending");
  }

  private static List<String> sorted(List<String> values) {
    return values == null ? List.of() : values.stream().distinct().sorted().toList();
  }

  private static boolean contains(String text, String... markers) {
    for (String marker : markers) {
      if (text.contains(marker)) {
        return true;
      }
    }
    return false;
  }
}
