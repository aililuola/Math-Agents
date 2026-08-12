package io.github.aililuola.mathproofmesh.proofcontrol;

import static io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.InferenceRiskType;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic taxonomy for load-bearing implication and scope errors. */
@SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "Risk markers are canonicalized with NFC and Locale.ROOT before deterministic matching")
public final class InferenceRiskScanner {
  private static final Map<InferenceRiskType, List<String>> MARKERS =
      Map.ofEntries(
          Map.entry(
              InferenceRiskType.NECESSARY_TO_SUFFICIENT,
              List.of("necessary therefore sufficient", "necessary condition proves")),
          Map.entry(
              InferenceRiskType.EVENTUAL_TO_GLOBAL,
              List.of("eventually therefore all", "sufficiently large hence every")),
          Map.entry(
              InferenceRiskType.POINTWISE_TO_UNIFORM,
              List.of("pointwise therefore uniform", "pointwise implies uniform")),
          Map.entry(
              InferenceRiskType.FINITE_RANGE_TO_FINITE_STATE,
              List.of("finite range therefore finite state", "bounded differences therefore periodic")),
          Map.entry(
              InferenceRiskType.IMAGE_INCLUSION_TO_SURJECTIVITY,
              List.of("image is contained", "image inclusion implies surjective")),
          Map.entry(
              InferenceRiskType.PROJECTION_TO_ORIGINAL,
              List.of("equal projections imply equal", "projection determines original")),
          Map.entry(
              InferenceRiskType.LOCAL_TO_GLOBAL,
              List.of("locally therefore globally", "local implies global")),
          Map.entry(
              InferenceRiskType.EXISTENCE_TO_UNIFORM_EXISTENCE,
              List.of("for each there exists therefore there exists one")),
          Map.entry(
              InferenceRiskType.PAIRWISE_TO_COMMON_WITNESS,
              List.of("pairwise therefore common witness")),
          Map.entry(
              InferenceRiskType.EMPIRICAL_TO_UNIVERSAL,
              List.of("checked cases therefore all", "samples prove universal")),
          Map.entry(
              InferenceRiskType.NONEMPTY_INTERSECTION_TO_SUBSET_CONTAINMENT,
              List.of("nonempty intersection therefore subset")),
          Map.entry(
              InferenceRiskType.EXISTS_COMPONENT_TO_ALL_COMPONENTS,
              List.of("one component therefore every component")),
          Map.entry(
              InferenceRiskType.PARTIAL_PROPERTY_TO_TOTAL_PROPERTY,
              List.of("partial property therefore total")),
          Map.entry(
              InferenceRiskType.COVERAGE_TO_EXHAUSTIVENESS,
              List.of("covers therefore exhaustive")));

  public List<ProofControlModels.InferenceRisk> scanText(
      String subjectId, String text) {
    String normalized =
        ProofIdentity.normalizeText(text).toLowerCase(Locale.ROOT);
    List<ProofControlModels.InferenceRisk> risks = new ArrayList<>();
    for (Map.Entry<InferenceRiskType, List<String>> entry : MARKERS.entrySet()) {
      if (entry.getValue().stream().anyMatch(normalized::contains)) {
        risks.add(risk(subjectId, entry.getKey(), List.of(subjectId), "", 0.95d));
      }
    }
    if (normalized.contains("for all") && normalized.contains("there exists")
        && normalized.indexOf("there exists") < normalized.indexOf("for all")) {
      risks.add(
          risk(
              subjectId,
              InferenceRiskType.QUANTIFIER_SWAP,
              List.of(subjectId),
              "",
              0.9d));
    }
    if (normalized.contains("therefore") && normalized.contains("assume")
        && normalized.contains("missing")) {
      risks.add(
          risk(
              subjectId,
              InferenceRiskType.DEPENDENCY_MISSING,
              List.of(subjectId),
              "",
              0.9d));
    }
    return List.copyOf(risks);
  }

  public List<ProofControlModels.InferenceRisk> scanScope(
      String subjectId,
      ProofControlModels.ScopeSignature premise,
      ProofControlModels.ScopeSignature conclusion,
      ScopeGuard guard) {
    ProofControlModels.ScopeRelation relation = guard.compare(premise, conclusion);
    if (relation == ProofControlModels.ScopeRelation.SAME
        || relation == ProofControlModels.ScopeRelation.CLAIM_STRONGER) {
      return List.of();
    }
    InferenceRiskType type =
        premise.indexScope() == ProofControlModels.IndexScope.EVENTUAL
                && conclusion.indexScope() == ProofControlModels.IndexScope.ALL
            ? InferenceRiskType.EVENTUAL_TO_GLOBAL
            : premise.uniformity() == ProofControlModels.UniformityScope.POINTWISE
                    && conclusion.uniformity()
                        == ProofControlModels.UniformityScope.UNIFORM
                ? InferenceRiskType.POINTWISE_TO_UNIFORM
                : InferenceRiskType.SCOPE_MISMATCH;
    return List.of(risk(subjectId, type, List.of(premise.subjectId()),
        conclusion.subjectId(), 0.95d));
  }

  public List<ProofControlModels.InferenceRisk> scanRelationStrengthening(
      String subjectId,
      ProofControlModels.RelationSignature premise,
      ProofControlModels.RelationSignature conclusion) {
    List<ProofControlModels.InferenceRisk> risks = new ArrayList<>();
    if (premise.setRelation() == ProofControlModels.SetRelationKind.NONEMPTY_INTERSECTION
        && conclusion.setRelation() == ProofControlModels.SetRelationKind.SUBSET) {
      risks.add(
          risk(
              subjectId,
              InferenceRiskType.NONEMPTY_INTERSECTION_TO_SUBSET_CONTAINMENT,
              List.of(subjectId),
              subjectId,
              1.0d));
    }
    if (premise.propertyStrength() == ProofControlModels.PropertyStrength.EXISTENTIAL
        && conclusion.propertyStrength() == ProofControlModels.PropertyStrength.UNIVERSAL) {
      risks.add(
          risk(
              subjectId,
              "component".equals(premise.semanticRole())
                  ? InferenceRiskType.EXISTS_COMPONENT_TO_ALL_COMPONENTS
                  : InferenceRiskType.SOME_WITNESS_TO_ALL_WITNESSES,
              List.of(subjectId),
              subjectId,
              1.0d));
    }
    if (premise.propertyStrength() == ProofControlModels.PropertyStrength.PARTIAL
        && conclusion.propertyStrength() == ProofControlModels.PropertyStrength.UNIVERSAL) {
      risks.add(
          risk(
              subjectId,
              InferenceRiskType.PARTIAL_PROPERTY_TO_TOTAL_PROPERTY,
              List.of(subjectId),
              subjectId,
              1.0d));
    }
    if ("coverage".equals(premise.semanticRole())
        && conclusion.propertyStrength() == ProofControlModels.PropertyStrength.EXHAUSTIVE) {
      risks.add(
          risk(
              subjectId,
              InferenceRiskType.COVERAGE_TO_EXHAUSTIVENESS,
              List.of(subjectId),
              subjectId,
              1.0d));
    }
    return List.copyOf(risks);
  }

  public ProofControlModels.InferenceRisk clearWithVerifiedBridge(
      ProofControlModels.InferenceRisk risk, String evidenceId) {
    ProofControlModels.required(evidenceId, "evidenceId");
    return new ProofControlModels.InferenceRisk(
        risk.id(),
        risk.type(),
        risk.subjectId(),
        risk.premiseIds(),
        risk.conclusionId(),
        risk.confidence(),
        risk.reason() + "; cleared by verified bridge " + evidenceId,
        false);
  }

  private static ProofControlModels.InferenceRisk risk(
      String subjectId,
      InferenceRiskType type,
      List<String> premises,
      String conclusion,
      double confidence) {
    String id =
        "risk_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "subject", subjectId,
                        "type", type.name(),
                        "premises", premises,
                        "conclusion", conclusion))
                .substring(0, 20);
    return new ProofControlModels.InferenceRisk(
        id,
        type,
        subjectId,
        premises,
        conclusion,
        confidence,
        type.name().toLowerCase(Locale.ROOT),
        true);
  }
}
