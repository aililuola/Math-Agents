package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.MetaPivotEffect;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.MetaPivotOutcome;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.MetaPivotStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.WakeCondition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exactly-once state machine for a route/round meta pivot. */
public final class MetaPivotController {
  public record Pivot(
      String pivotId,
      String routeId,
      int round,
      MetaPivotStatus status,
      List<String> requestedMechanisms,
      MetaPivotOutcome outcome,
      List<String> audit) {
    public Pivot {
      pivotId = ProofControlModels.required(pivotId, "pivotId");
      routeId = ProofControlModels.required(routeId, "routeId");
      if (round < 0) {
        throw new IllegalArgumentException("round must be nonnegative");
      }
      requestedMechanisms = List.copyOf(requestedMechanisms);
      audit = List.copyOf(audit);
    }
  }

  private final Map<String, Pivot> pivots = new LinkedHashMap<>();

  public synchronized Pivot request(String routeId, int round, List<String> mechanisms) {
    List<String> canonical =
        (mechanisms == null ? List.<String>of() : mechanisms).stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::strip)
            .distinct()
            .sorted()
            .toList();
    if (canonical.isEmpty()) {
      throw new IllegalArgumentException("at least one pivot mechanism is required");
    }
    String pivotId =
        "pivot_"
            + CanonicalJson.stableHash(
                    Map.of("route_id", routeId, "round", round, "mechanisms", canonical))
                .substring(0, 20);
    return pivots.computeIfAbsent(
        pivotId,
        ignored ->
            new Pivot(
                pivotId,
                routeId,
                round,
                MetaPivotStatus.REQUESTED,
                canonical,
                null,
                List.of("requested")));
  }

  public synchronized Pivot admit(String pivotId, boolean admitted, String evidenceId) {
    Pivot pivot = required(pivotId);
    if (pivot.status() != MetaPivotStatus.REQUESTED) {
      return pivot;
    }
    return put(
        pivot,
        admitted ? MetaPivotStatus.ADMITTED : MetaPivotStatus.FAILED,
        null,
        (admitted ? "admitted:" : "rejected:") + ProofControlModels.required(
            evidenceId, "evidenceId"));
  }

  public synchronized Pivot execute(
      String pivotId,
      List<String> completedMechanisms,
      List<String> materialStateRefs,
      List<WakeCondition> wakeConditions,
      String reason) {
    Pivot pivot = required(pivotId);
    if (pivot.status() == MetaPivotStatus.APPLIED
        || pivot.status() == MetaPivotStatus.EVALUATED) {
      return pivot;
    }
    if (pivot.status() != MetaPivotStatus.ADMITTED) {
      throw new IllegalStateException("pivot must be admitted before execution");
    }
    put(pivot, MetaPivotStatus.EXECUTING, null, "execution began");
    List<String> completed =
        completedMechanisms == null
            ? List.of()
            : completedMechanisms.stream().distinct().sorted().toList();
    List<String> stateRefs =
        materialStateRefs == null ? List.of() : materialStateRefs.stream().distinct().sorted().toList();
    List<WakeCondition> wakes =
        wakeConditions == null
            ? List.of()
            : wakeConditions.stream().sorted(Comparator.comparing(WakeCondition::id)).toList();
    MetaPivotEffect effect;
    if (!stateRefs.isEmpty()) {
      effect = MetaPivotEffect.MATERIALIZED_NO_GAIN;
    } else if (!wakes.isEmpty()) {
      effect = MetaPivotEffect.DEFERRED;
    } else {
      effect = MetaPivotEffect.EMPTY;
    }
    MetaPivotOutcome outcome =
        new MetaPivotOutcome(
            pivot.pivotId(),
            effect,
            pivot.requestedMechanisms(),
            completed,
            stateRefs,
            wakes,
            ProofControlModels.required(reason, "reason"));
    return put(required(pivotId), MetaPivotStatus.APPLIED, outcome, "execution applied");
  }

  public synchronized Pivot evaluate(String pivotId, boolean independentReviewAccepted) {
    return evaluate(pivotId, independentReviewAccepted, GainEvidence.none());
  }

  /** Evaluates mathematical gain separately from proposal or state materialization. */
  public synchronized Pivot evaluate(
      String pivotId,
      boolean independentReviewAccepted,
      GainEvidence gain) {
    Pivot pivot = required(pivotId);
    if (pivot.status() == MetaPivotStatus.EVALUATED) {
      return pivot;
    }
    if (pivot.status() != MetaPivotStatus.APPLIED) {
      throw new IllegalStateException("pivot must be applied before evaluation");
    }
    GainEvidence observed = gain == null ? GainEvidence.none() : gain;
    if (!independentReviewAccepted
        && (pivot.outcome().effect() == MetaPivotEffect.EFFECTIVE
            || pivot.outcome().effect() == MetaPivotEffect.MATERIALIZED_NO_GAIN)) {
      MetaPivotOutcome demoted =
          new MetaPivotOutcome(
              pivot.outcome().pivotId(),
              MetaPivotEffect.FAILED,
              pivot.outcome().attemptedMechanisms(),
              pivot.outcome().completedMechanisms(),
              pivot.outcome().materialStateRefs(),
              pivot.outcome().wakeConditions(),
              "independent review rejected claimed material progress");
      return put(pivot, MetaPivotStatus.EVALUATED, demoted, "independent review rejected");
    }
    if (pivot.outcome().effect() == MetaPivotEffect.MATERIALIZED_NO_GAIN) {
      MetaPivotEffect effect =
          observed.hasVerifiedGain()
              ? MetaPivotEffect.EFFECTIVE
              : MetaPivotEffect.MATERIALIZED_NO_GAIN;
      String reason =
          observed.hasVerifiedGain()
              ? "independent review confirmed measurable mathematical gain"
              : "proposal materialized but produced no verified mathematical gain";
      MetaPivotOutcome evaluated =
          new MetaPivotOutcome(
              pivot.outcome().pivotId(),
              effect,
              pivot.outcome().attemptedMechanisms(),
              pivot.outcome().completedMechanisms(),
              pivot.outcome().materialStateRefs(),
              pivot.outcome().wakeConditions(),
              reason);
      return put(pivot, MetaPivotStatus.EVALUATED, evaluated, reason);
    }
    return put(pivot, MetaPivotStatus.EVALUATED, pivot.outcome(), "independent review accepted");
  }

  public record GainEvidence(
      int verifiedFactGain,
      int obligationsClosed,
      int independentRoutesExecuted,
      double proofDebtBefore,
      double proofDebtAfter) {
    public GainEvidence {
      if (verifiedFactGain < 0
          || obligationsClosed < 0
          || independentRoutesExecuted < 0
          || !Double.isFinite(proofDebtBefore)
          || !Double.isFinite(proofDebtAfter)
          || proofDebtBefore < 0.0d
          || proofDebtAfter < 0.0d) {
        throw new IllegalArgumentException("invalid meta-pivot gain evidence");
      }
    }

    public boolean hasVerifiedGain() {
      return verifiedFactGain > 0
          || obligationsClosed > 0
          || independentRoutesExecuted > 0
          || proofDebtAfter < proofDebtBefore;
    }

    public static GainEvidence none() {
      return new GainEvidence(0, 0, 0, 0.0d, 0.0d);
    }
  }

  public synchronized Pivot get(String pivotId) {
    return required(pivotId);
  }

  private Pivot put(
      Pivot pivot, MetaPivotStatus status, MetaPivotOutcome outcome, String auditEvent) {
    List<String> audit = new ArrayList<>(pivot.audit());
    audit.add(auditEvent);
    Pivot updated =
        new Pivot(
            pivot.pivotId(),
            pivot.routeId(),
            pivot.round(),
            status,
            pivot.requestedMechanisms(),
            outcome,
            audit);
    pivots.put(updated.pivotId(), updated);
    return updated;
  }

  private Pivot required(String pivotId) {
    Pivot pivot = pivots.get(pivotId);
    if (pivot == null) {
      throw new IllegalArgumentException("unknown pivot: " + pivotId);
    }
    return pivot;
  }
}
