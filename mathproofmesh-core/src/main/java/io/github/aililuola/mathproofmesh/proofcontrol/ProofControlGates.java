package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Continue and synthesis gates driven only by verifiable progress. */
public final class ProofControlGates {
  public record ContinueDecision(
      String id,
      String routeId,
      int segment,
      ProofControlModels.GateVerdict verdict,
      int consecutiveNoProgress,
      String reason) {}

  public record SynthesisDecision(
      String id,
      ProofControlModels.GateVerdict verdict,
      List<String> openCoreObligationIds,
      List<String> openScopeRiskIds,
      List<String> unresolvedConflictIds,
      List<String> invalidGoalLinkIds,
      List<String> unresolvedCommonModeIds,
      boolean candidateProofVerified,
      List<String> reasons) {
    public SynthesisDecision {
      openCoreObligationIds = List.copyOf(openCoreObligationIds);
      openScopeRiskIds = List.copyOf(openScopeRiskIds);
      unresolvedConflictIds = List.copyOf(unresolvedConflictIds);
      invalidGoalLinkIds = List.copyOf(invalidGoalLinkIds);
      unresolvedCommonModeIds = List.copyOf(unresolvedCommonModeIds);
      reasons = List.copyOf(reasons);
    }
  }

  public ContinueDecision continueDeepening(
      ProofControlModels.Mode mode,
      String routeId,
      int segment,
      int previousNoProgress,
      boolean coreObligationClosed,
      boolean coreDebtReduced,
      boolean firstErrorChanged,
      boolean verifiedBridgeGain,
      int maximumNoProgressSegments) {
    boolean progress =
        coreObligationClosed
            || coreDebtReduced
            || firstErrorChanged
            || verifiedBridgeGain;
    int noProgress = progress ? 0 : previousNoProgress + 1;
    boolean blocked = noProgress >= maximumNoProgressSegments;
    ProofControlModels.GateVerdict verdict =
        blocked
            ? mode == ProofControlModels.Mode.ACTIVE
                ? ProofControlModels.GateVerdict.BLOCK
                : mode == ProofControlModels.Mode.SHADOW
                    ? ProofControlModels.GateVerdict.SHADOW_BLOCK
                    : ProofControlModels.GateVerdict.PASS
            : ProofControlModels.GateVerdict.PASS;
    String reason =
        progress
            ? "verified core progress reset stagnation"
            : blocked
                ? "repeated segment has no core progress"
                : "one bounded no-progress segment remains admissible";
    String id =
        "continue_gate_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "route", routeId,
                        "segment", segment,
                        "no_progress", noProgress,
                        "verdict", verdict.name()))
                .substring(0, 20);
    return new ContinueDecision(id, routeId, segment, verdict, noProgress, reason);
  }

  public SynthesisDecision synthesisReadiness(
      ProofControlModels.Mode mode,
      List<String> openCoreObligationIds,
      List<String> openScopeRiskIds,
      List<String> unresolvedConflictIds,
      List<ProofControlModels.GoalLink> goalLinks,
      List<String> unresolvedCommonModeIds,
      boolean candidateProofVerified,
      List<String> explicitCandidateDependencies) {
    List<String> core =
        candidateProofVerified
            ? openCoreObligationIds.stream()
                .filter(explicitCandidateDependencies::contains)
                .sorted()
                .toList()
            : openCoreObligationIds.stream().sorted().toList();
    List<String> invalidLinks =
        goalLinks.stream()
            .filter(
                value ->
                    value.relation() == ProofControlModels.GoalRelation.NECESSARY_ONLY
                        || value.relation() == ProofControlModels.GoalRelation.UNKNOWN
                        || value.scopeRelation()
                            == ProofControlModels.ScopeRelation.CLAIM_WEAKER)
            .map(ProofControlModels.GoalLink::linkId)
            .sorted()
            .toList();
    List<String> reasons = new ArrayList<>();
    if (!core.isEmpty()) {
      reasons.add("open core obligations remain");
    }
    if (!openScopeRiskIds.isEmpty()) {
      reasons.add("open scope or inference risks remain");
    }
    if (!unresolvedConflictIds.isEmpty()) {
      reasons.add("unresolved proof-graph conflicts remain");
    }
    if (!invalidLinks.isEmpty()) {
      reasons.add("goal links are not sufficient");
    }
    if (!unresolvedCommonModeIds.isEmpty()) {
      reasons.add("unresolved common-mode assumptions remain");
    }
    ProofControlModels.GateVerdict desired =
        reasons.isEmpty()
            ? ProofControlModels.GateVerdict.PASS
            : ProofControlModels.GateVerdict.BLOCK;
    ProofControlModels.GateVerdict verdict =
        desired == ProofControlModels.GateVerdict.BLOCK
                && mode == ProofControlModels.Mode.SHADOW
            ? ProofControlModels.GateVerdict.SHADOW_BLOCK
            : mode == ProofControlModels.Mode.OFF
                ? ProofControlModels.GateVerdict.PASS
                : desired;
    String id =
        "synthesis_ready_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "core", core,
                        "risks", openScopeRiskIds,
                        "conflicts", unresolvedConflictIds,
                        "links", invalidLinks,
                        "common", unresolvedCommonModeIds,
                        "candidate_verified", candidateProofVerified,
                        "verdict", verdict.name()))
                .substring(0, 20);
    return new SynthesisDecision(
        id,
        verdict,
        core,
        openScopeRiskIds,
        unresolvedConflictIds,
        invalidLinks,
        unresolvedCommonModeIds,
        candidateProofVerified,
        reasons);
  }
}
