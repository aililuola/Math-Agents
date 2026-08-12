package io.github.aililuola.mathproofmesh.orchestration.teams;

import java.util.ArrayList;
import java.util.List;

/** Applies risk classification and the independent route-team review gate. */
public final class RouteTeam {
  private final double skepticThreshold;

  public RouteTeam(double skepticThreshold) {
    if (!Double.isFinite(skepticThreshold)
        || skepticThreshold < 0.0d
        || skepticThreshold > 1.0d) {
      throw new IllegalArgumentException("skepticThreshold must be in [0,1]");
    }
    this.skepticThreshold = skepticThreshold;
  }

  public RiskAssessment classifyRisk(RiskSignals signals) {
    java.util.Objects.requireNonNull(signals, "signals");
    double score = 0.0d;
    ArrayList<String> reasons = new ArrayList<>();
    score += add(signals.keyStep(), 0.18d, "contains a key proof step", reasons);
    score += add(signals.externalTheorem(), 0.16d, "uses an external theorem", reasons);
    score += add(signals.quantifierTransform(), 0.14d, "quantifier transformation", reasons);
    score +=
        add(signals.numericalEvidence(), 0.30d, "numerical evidence needs replay", reasons);
    score +=
        add(signals.lowConfidence(), 0.16d, "low local confidence", reasons);
    score +=
        add(signals.structuralFailure(), 0.30d, "structural review did not pass", reasons);
    score +=
        add(signals.repeatedFirstError(), 0.18d, "repeated first-error location", reasons);
    score +=
        add(signals.salvagedPartial(), 0.30d, "salvaged partial delta", reasons);
    score +=
        add(signals.enteringGlobalFactGate(), 0.20d, "entering global Fact gate", reasons);
    score = Math.min(1.0d, score);
    boolean mandatory =
        signals.keyStep()
            || signals.numericalEvidence()
            || signals.structuralFailure()
            || signals.repeatedFirstError()
            || signals.salvagedPartial()
            || signals.enteringGlobalFactGate();
    return new RiskAssessment(
        score,
        reasons,
        mandatory || score >= skepticThreshold,
        signals.numericalEvidence() || signals.toolRequested(),
        signals.enteringGlobalFactGate());
  }

  public RouteTeamResult review(
      RouteTeamPlan plan,
      boolean skepticPassed,
      boolean toolReplayPassed,
      boolean refereePassed) {
    ArrayList<String> diagnostics = new ArrayList<>(plan.diagnostics());
    boolean skepticRequired = plan.skeptic() != null;
    boolean toolRequired = plan.toolSpecialist() != null;
    boolean share =
        plan.globalShareAllowed()
            && (!skepticRequired || skepticPassed)
            && (!toolRequired || toolReplayPassed)
            && refereePassed;
    if (skepticRequired && !skepticPassed) {
      diagnostics.add("skeptic did not pass; artifact remains route-local");
    }
    if (toolRequired && !toolReplayPassed) {
      diagnostics.add("tool evidence was not independently replayed");
    }
    if (!refereePassed) {
      diagnostics.add("independent referee did not admit the artifact");
    }
    return new RouteTeamResult(
        plan.routeId(),
        skepticPassed,
        toolReplayPassed,
        refereePassed,
        share,
        diagnostics);
  }

  private static double add(
      boolean present, double weight, String reason, List<String> reasons) {
    if (present) {
      reasons.add(reason);
      return weight;
    }
    return 0.0d;
  }

  public record RiskSignals(
      boolean keyStep,
      boolean externalTheorem,
      boolean quantifierTransform,
      boolean numericalEvidence,
      boolean lowConfidence,
      boolean structuralFailure,
      boolean repeatedFirstError,
      boolean salvagedPartial,
      boolean enteringGlobalFactGate,
      boolean toolRequested) {}
}
