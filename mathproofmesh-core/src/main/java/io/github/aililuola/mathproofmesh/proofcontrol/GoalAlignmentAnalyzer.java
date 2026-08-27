package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Determines sufficiency from identity or verified implication, never overlap. */
public final class GoalAlignmentAnalyzer {
  @FunctionalInterface
  public interface VerifiedImplications {
    boolean implies(String sourceId, String targetId);
  }

  public record ContractResult(
      String id,
      boolean confidencePassed,
      boolean outlinePresent,
      boolean unknownResolved,
      String countermodelActionId,
      ProofControlModels.GateVerdict verdict,
      List<String> reasons) {
    public ContractResult {
      reasons = List.copyOf(reasons);
    }
  }

  public ProofControlModels.GoalLink assess(
      String subjectId,
      String subjectStatement,
      ProofControlModels.ScopeSignature subjectScope,
      ProofControlModels.Obligation target,
      ProofControlModels.ScopeSignature targetScope,
      ScopeGuard scopeGuard,
      VerifiedImplications graph) {
    String sourceIdentity = ProofIdentity.obligationIdentityText(subjectStatement);
    String targetIdentity = ProofIdentity.obligationIdentityText(target.statement());
    ProofControlModels.ScopeRelation scope = scopeGuard.compare(subjectScope, targetScope);
    ProofControlModels.GoalRelation relation;
    List<String> outline = new ArrayList<>();
    double confidence;
    if (sourceIdentity.equals(targetIdentity)
        && scope == ProofControlModels.ScopeRelation.SAME) {
      relation = ProofControlModels.GoalRelation.EQUIVALENT;
      outline.add("canonical statement and scope are identical");
      confidence = 1.0d;
    } else if (graph != null && graph.implies(subjectId, target.id())) {
      relation = ProofControlModels.GoalRelation.SUFFICIENT;
      outline.add("verified graph implication " + subjectId + " -> " + target.id());
      confidence = 1.0d;
    } else if (graph != null && graph.implies(target.id(), subjectId)) {
      relation = ProofControlModels.GoalRelation.NECESSARY_ONLY;
      outline.add("verified reverse implication " + target.id() + " -> " + subjectId);
      confidence = 1.0d;
    } else {
      relation = ProofControlModels.GoalRelation.UNKNOWN;
      confidence = 0.0d;
    }
    String linkId =
        "goal_link_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "subject", subjectId,
                        "target", target.id(),
                        "relation", relation.name(),
                        "scope", scope.name()))
                .substring(0, 20);
    return new ProofControlModels.GoalLink(
        linkId,
        subjectId,
        target.id(),
        relation,
        scope,
        outline,
        relation == ProofControlModels.GoalRelation.EQUIVALENT
                || relation == ProofControlModels.GoalRelation.SUFFICIENT
            ? List.of()
            : List.of(target.id()),
        relation == ProofControlModels.GoalRelation.NECESSARY_ONLY
            ? List.of("bridge_for_" + subjectId)
            : List.of(),
        relation == ProofControlModels.GoalRelation.EQUIVALENT ? 1.0d : 0.5d,
        confidence,
        List.of());
  }

  public ContractResult enforce(
      ProofControlModels.GoalLink link,
      double minimumConfidence,
      boolean directPremiseException,
      List<String> exceptionEvidenceIds) {
    ProofControlModels.unit(minimumConfidence, "minimumConfidence");
    boolean confidence = link.confidence() >= minimumConfidence;
    boolean outline =
        !link.implicationOutline().isEmpty()
            || link.relation() == ProofControlModels.GoalRelation.UNRELATED
            || link.relation() == ProofControlModels.GoalRelation.HEURISTIC_ONLY;
    boolean unknown = link.relation() != ProofControlModels.GoalRelation.UNKNOWN;
    boolean validException =
        directPremiseException
            && exceptionEvidenceIds != null
            && !exceptionEvidenceIds.isEmpty();
    List<String> reasons = new ArrayList<>();
    if (!confidence && !validException) {
      reasons.add("alignment confidence below threshold");
    }
    if (!outline && !validException) {
      reasons.add("implication outline is required");
    }
    if (!unknown && !validException) {
      reasons.add("unknown relation requires countermodel work");
    }
    String countermodelActionId =
        !unknown && !validException ? "countermodel_for_" + link.subjectId() : null;
    ProofControlModels.GateVerdict verdict =
        reasons.isEmpty()
            ? ProofControlModels.GateVerdict.PASS
            : ProofControlModels.GateVerdict.BLOCK;
    String id =
        "alignment_contract_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "link", link.linkId(),
                        "minimum_confidence", minimumConfidence,
                        "exception", validException))
                .substring(0, 20);
    return new ContractResult(
        id, confidence, outline, unknown, countermodelActionId, verdict, reasons);
  }

  public boolean directPremiseClosure(
      String obligationStatement, List<String> admittedPremises) {
    String target = ProofIdentity.obligationIdentityText(obligationStatement);
    return admittedPremises.stream()
        .map(ProofIdentity::obligationIdentityText)
        .anyMatch(target::equals);
  }
}
