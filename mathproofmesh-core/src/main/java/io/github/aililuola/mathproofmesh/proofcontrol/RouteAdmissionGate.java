package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admits a route only after blueprint, alignment, scope, and independence gates. */
public final class RouteAdmissionGate {
  public record Decision(
      String id,
      String strategyId,
      ProofControlModels.GateVerdict verdict,
      List<String> targetObligationIds,
      List<String> reasons,
      String rewriteRequestId) {
    public Decision {
      targetObligationIds = List.copyOf(targetObligationIds);
      reasons = List.copyOf(reasons);
      rewriteRequestId = ProofControlModels.blankToNull(rewriteRequestId);
    }

    public boolean blocksRuntime(ProofControlModels.Mode mode) {
      return mode == ProofControlModels.Mode.ACTIVE
          && (verdict == ProofControlModels.GateVerdict.BLOCK
              || verdict == ProofControlModels.GateVerdict.REWRITE);
    }
  }

  public Decision evaluate(
      ProofControlModels.Mode mode,
      ProofControlModels.Strategy strategy,
      StrategyBlueprintCompiler.Compilation compilation,
      ProofControlModels.GoalLink link,
      boolean mechanismDuplicate,
      boolean unresolvedCommonModeRisk) {
    List<String> reasons = new ArrayList<>();
    ProofControlModels.GateVerdict desired = ProofControlModels.GateVerdict.PASS;
    if (!"accepted".equals(compilation.blueprint().status())) {
      reasons.add("strategy blueprint failed semantic admission");
      desired = ProofControlModels.GateVerdict.BLOCK;
    }
    if (link.relation() == ProofControlModels.GoalRelation.NECESSARY_ONLY
        && link.requiredBridgeIds().isEmpty()) {
      reasons.add("necessary-only target has no sufficient bridge");
      desired = ProofControlModels.GateVerdict.REWRITE;
    }
    if (link.scopeRelation() == ProofControlModels.ScopeRelation.CLAIM_STRONGER
        && link.minimalityScore() < 0.75d) {
      reasons.add("target is overstrong relative to the main goal");
      desired = ProofControlModels.GateVerdict.REWRITE;
    }
    if (link.relation() == ProofControlModels.GoalRelation.UNKNOWN) {
      reasons.add("goal relation is unknown");
      desired = ProofControlModels.GateVerdict.BLOCK;
    }
    if (mechanismDuplicate) {
      reasons.add("route duplicates an admitted mechanism");
      desired = ProofControlModels.GateVerdict.BLOCK;
    }
    if (unresolvedCommonModeRisk) {
      reasons.add("route shares an unresolved load-bearing assumption");
      desired = ProofControlModels.GateVerdict.BLOCK;
    }
    ProofControlModels.GateVerdict verdict =
        mode == ProofControlModels.Mode.SHADOW
                && desired != ProofControlModels.GateVerdict.PASS
            ? ProofControlModels.GateVerdict.SHADOW_BLOCK
            : mode == ProofControlModels.Mode.OFF
                ? ProofControlModels.GateVerdict.PASS
                : desired;
    String rewrite =
        desired == ProofControlModels.GateVerdict.REWRITE
            ? "blueprint_rewrite_"
                + CanonicalJson.stableHash(
                        Map.of(
                            "strategy", strategy.id(),
                            "blueprint", compilation.blueprint().id(),
                            "reasons", reasons))
                    .substring(0, 20)
            : null;
    String id =
        "route_admission_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "strategy", strategy.id(),
                        "blueprint", compilation.blueprint().id(),
                        "verdict", verdict.name(),
                        "reasons", reasons))
                .substring(0, 20);
    return new Decision(
        id,
        strategy.id(),
        verdict,
        List.of(link.targetObligationId()),
        reasons,
        rewrite);
  }
}
