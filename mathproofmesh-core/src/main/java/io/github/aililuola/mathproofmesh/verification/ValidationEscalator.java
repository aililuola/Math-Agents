package io.github.aililuola.mathproofmesh.verification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Selects a risk-driven ladder and records every unavailable escalation path. */
public final class ValidationEscalator {
  private final ValidationEscalationPolicy policy;

  public ValidationEscalator(ValidationEscalationPolicy policy) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
  }

  public EscalationPlan plan(
      double riskScore,
      Collection<String> reviewerVerdicts,
      boolean crossProviderAvailable,
      boolean toolOrFormalAvailable,
      boolean beforeFactPromotion,
      boolean finalProof) {
    if (!policy.enabled()) {
      return new EscalationPlan(riskScore, List.of(), List.of(), false);
    }
    List<ValidationLevel> levels = new ArrayList<>();
    List<String> diagnostics = new ArrayList<>();
    if (policy.deterministicChecksFirst()) {
      levels.add(ValidationLevel.DETERMINISTIC);
    }
    SetView verdicts = SetView.of(reviewerVerdicts);
    boolean disagreement = verdicts.size() > 1;
    boolean highRisk = riskScore >= policy.highRiskThreshold();
    boolean shouldEscalate =
        highRisk
            || (disagreement && policy.escalateOnReviewerDisagreement())
            || (beforeFactPromotion && policy.escalateBeforeFactPromotion())
            || (finalProof && policy.escalateFinalProof());
    if (shouldEscalate && policy.blindSameModelReview()) {
      levels.add(ValidationLevel.BLIND_SAME_MODEL);
    }
    if (shouldEscalate && policy.adversarialPromptReview()) {
      levels.add(ValidationLevel.ADVERSARIAL_BLIND);
    }
    if (shouldEscalate && policy.crossProviderReview()) {
      if (crossProviderAvailable) {
        levels.add(ValidationLevel.CROSS_PROVIDER);
      } else {
        diagnostics.add(
            "cross-provider reviewer unavailable; using adversarial/tool fallback");
      }
    }
    if (highRisk && policy.toolOrFormalCheckOnHighRisk()) {
      if (toolOrFormalAvailable) {
        levels.add(ValidationLevel.TOOL_OR_FORMAL);
      } else {
        diagnostics.add("tool/formal backend unavailable; result remains pending");
      }
    }
    return new EscalationPlan(
        riskScore,
        List.copyOf(new LinkedHashSet<>(levels)),
        diagnostics,
        beforeFactPromotion && shouldEscalate);
  }

  private record SetView(java.util.Set<String> values) {
    static SetView of(Collection<String> source) {
      java.util.Set<String> values = new LinkedHashSet<>();
      if (source != null) {
        source.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .forEach(values::add);
      }
      return new SetView(java.util.Set.copyOf(values));
    }

    int size() {
      return values.size();
    }
  }
}
