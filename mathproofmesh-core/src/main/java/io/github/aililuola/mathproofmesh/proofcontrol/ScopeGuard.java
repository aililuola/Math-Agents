package io.github.aililuola.mathproofmesh.proofcontrol;

import static io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.IndexScope;
import static io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObjectScope;
import static io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ScopeRelation;
import static io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.UniformityScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Extracts and compares index, uniformity, object, and quantifier scope. */
public final class ScopeGuard {
  private static final Pattern BOUNDED =
      Pattern.compile("\\b\\d+\\s*(?:<=|≤)\\s*[a-z]\\s*(?:<=|≤)\\s*\\d+\\b");
  private final double confidenceThreshold;

  public ScopeGuard() {
    this(0.7d);
  }

  public ScopeGuard(double confidenceThreshold) {
    ProofControlModels.unit(confidenceThreshold, "confidenceThreshold");
    this.confidenceThreshold = confidenceThreshold;
  }

  public ProofControlModels.ScopeSignature extract(
      String subjectId,
      String text,
      List<ProofControlModels.Quantifier> quantifiers,
      double baseConfidence) {
    String normalized =
        ProofIdentity.normalizeText(text).toLowerCase(Locale.ROOT);
    IndexScope index = indexScope(normalized, quantifiers);
    UniformityScope uniformity = uniformity(normalized, quantifiers);
    ObjectScope object = objectScope(normalized);
    int detections = 0;
    detections += index == IndexScope.UNKNOWN ? 0 : 1;
    detections += uniformity == UniformityScope.UNKNOWN ? 0 : 1;
    detections += object == ObjectScope.UNKNOWN ? 0 : 1;
    detections += quantifiers == null || quantifiers.isEmpty() ? 0 : 1;
    double confidence = Math.min(1.0d, Math.max(baseConfidence, 0.45d + detections * 0.12d));
    return new ProofControlModels.ScopeSignature(
        subjectId,
        index,
        uniformity,
        object,
        quantifiers,
        List.of(),
        List.of(),
        confidence);
  }

  public ScopeRelation compare(
      ProofControlModels.ScopeSignature premise,
      ProofControlModels.ScopeSignature conclusion) {
    List<ScopeRelation> relations = new ArrayList<>();
    relations.add(
        ordered(
            premise.indexScope(),
            conclusion.indexScope(),
            Map.of(
                IndexScope.SINGLE_INSTANCE, 1,
                IndexScope.FINITE_PREFIX, 2,
                IndexScope.BOUNDED_RANGE, 2,
                IndexScope.EVENTUAL, 3,
                IndexScope.ALL, 4),
            Set.of(
                Set.of(IndexScope.EVENTUAL, IndexScope.FINITE_PREFIX),
                Set.of(IndexScope.EVENTUAL, IndexScope.BOUNDED_RANGE),
                Set.of(IndexScope.FINITE_PREFIX, IndexScope.BOUNDED_RANGE))));
    relations.add(
        ordered(
            premise.uniformity(),
            conclusion.uniformity(),
            Map.of(
                UniformityScope.EXISTS_PER_INSTANCE, 1,
                UniformityScope.POINTWISE, 2,
                UniformityScope.UNIFORM, 3),
            Set.of()));
    relations.add(objectRelation(premise.objectScope(), conclusion.objectScope()));
    relations.add(quantifierRelation(premise.quantifiers(), conclusion.quantifiers()));

    List<ScopeRelation> material =
        relations.stream().filter(value -> value != ScopeRelation.SAME).toList();
    if (material.isEmpty()) {
      return ScopeRelation.SAME;
    }
    if (material.contains(ScopeRelation.INCOMPARABLE)) {
      return ScopeRelation.INCOMPARABLE;
    }
    List<ScopeRelation> known =
        material.stream().filter(value -> value != ScopeRelation.UNKNOWN).toList();
    if (known.isEmpty()) {
      return ScopeRelation.UNKNOWN;
    }
    if (known.contains(ScopeRelation.CLAIM_STRONGER)
        && known.contains(ScopeRelation.CLAIM_WEAKER)) {
      return ScopeRelation.INCOMPARABLE;
    }
    return material.contains(ScopeRelation.UNKNOWN) ? ScopeRelation.UNKNOWN : known.getFirst();
  }

  public boolean canClose(
      ProofControlModels.ScopeSignature premise,
      ProofControlModels.ScopeSignature conclusion) {
    ScopeRelation relation = compare(premise, conclusion);
    boolean structured =
        conclusion.indexScope() != IndexScope.UNKNOWN
            || conclusion.uniformity() != UniformityScope.UNKNOWN
            || conclusion.objectScope() != ObjectScope.UNKNOWN
            || !conclusion.quantifiers().isEmpty();
    return (relation == ScopeRelation.SAME || relation == ScopeRelation.CLAIM_STRONGER)
        && (!structured || premise.confidence() >= confidenceThreshold);
  }

  public boolean canPromoteFact(ProofControlModels.ScopeSignature signature) {
    return signature.confidence() >= confidenceThreshold;
  }

  private static IndexScope indexScope(
      String text, List<ProofControlModels.Quantifier> quantifiers) {
    if (has(text, "eventually", "sufficiently large", "for all large", "最终", "充分大")) {
      return IndexScope.EVENTUAL;
    }
    if (has(text, "finite prefix", "first n terms", "initial segment", "有限前缀")) {
      return IndexScope.FINITE_PREFIX;
    }
    if (has(text, "bounded range", "bounded interval", "有界范围")
        || BOUNDED.matcher(text).find()) {
      return IndexScope.BOUNDED_RANGE;
    }
    if ((quantifiers != null
            && quantifiers.stream().anyMatch(value -> "forall".equals(value.kind())))
        || has(text, "for all", "for every", "each integer", "∀", "任意", "所有")) {
      return IndexScope.ALL;
    }
    if (has(text, "at n =", "single instance", "单个实例")) {
      return IndexScope.SINGLE_INSTANCE;
    }
    return IndexScope.UNKNOWN;
  }

  private static UniformityScope uniformity(
      String text, List<ProofControlModels.Quantifier> quantifiers) {
    if (has(text, "uniform", "一致")) {
      return UniformityScope.UNIFORM;
    }
    if (has(text, "pointwise", "逐点")) {
      return UniformityScope.POINTWISE;
    }
    if (quantifiers != null && !quantifiers.isEmpty()) {
      List<ProofControlModels.Quantifier> ordered =
          quantifiers.stream().sorted(Comparator.comparingInt(ProofControlModels.Quantifier::order))
              .toList();
      int forall = firstIndex(ordered, "forall");
      int exists = firstNonForall(ordered);
      if (forall >= 0 && exists >= 0) {
        return forall < exists
            ? UniformityScope.EXISTS_PER_INSTANCE
            : UniformityScope.UNIFORM;
      }
    }
    if (has(text, "for each", "depending on", "may depend on", "分别存在")) {
      return UniformityScope.EXISTS_PER_INSTANCE;
    }
    return UniformityScope.UNKNOWN;
  }

  private static ObjectScope objectScope(String text) {
    if (has(text, "projection", "projected", "投影")) {
      return ObjectScope.PROJECTION;
    }
    if (has(text, "quotient", "modulo equivalence", "商空间")) {
      return ObjectScope.QUOTIENT;
    }
    if (has(text, "residue class", "congruence class", "剩余类", "同余类")) {
      return ObjectScope.RESIDUE_CLASSES;
    }
    if (has(text, "substructure", "subgroup", "subspace", "子结构")) {
      return ObjectScope.SUBSTRUCTURE;
    }
    if (has(text, "original object", "full object", "entire object", "原对象", "完整对象")) {
      return ObjectScope.FULL_OBJECT;
    }
    return ObjectScope.UNKNOWN;
  }

  private static <T extends Enum<T>> ScopeRelation ordered(
      T premise, T conclusion, Map<T, Integer> ranks, Set<Set<T>> incomparable) {
    if (premise == conclusion) {
      return ScopeRelation.SAME;
    }
    if (premise.name().equals("UNKNOWN") || conclusion.name().equals("UNKNOWN")) {
      return ScopeRelation.UNKNOWN;
    }
    if (incomparable.contains(Set.of(premise, conclusion))) {
      return ScopeRelation.INCOMPARABLE;
    }
    Integer left = ranks.get(premise);
    Integer right = ranks.get(conclusion);
    if (left == null || right == null) {
      return ScopeRelation.UNKNOWN;
    }
    return left > right ? ScopeRelation.CLAIM_STRONGER : ScopeRelation.CLAIM_WEAKER;
  }

  private static ScopeRelation objectRelation(ObjectScope premise, ObjectScope conclusion) {
    if (premise == conclusion) {
      return ScopeRelation.SAME;
    }
    if (premise == ObjectScope.UNKNOWN || conclusion == ObjectScope.UNKNOWN) {
      return ScopeRelation.UNKNOWN;
    }
    if (premise == ObjectScope.FULL_OBJECT) {
      return ScopeRelation.CLAIM_STRONGER;
    }
    if (conclusion == ObjectScope.FULL_OBJECT) {
      return ScopeRelation.CLAIM_WEAKER;
    }
    return ScopeRelation.INCOMPARABLE;
  }

  private static ScopeRelation quantifierRelation(
      List<ProofControlModels.Quantifier> premise,
      List<ProofControlModels.Quantifier> conclusion) {
    if (premise.equals(conclusion)) {
      return ScopeRelation.SAME;
    }
    return premise.isEmpty() || conclusion.isEmpty()
        ? ScopeRelation.UNKNOWN
        : ScopeRelation.INCOMPARABLE;
  }

  private static boolean has(String text, String... values) {
    for (String value : values) {
      if (text.contains(value)) {
        return true;
      }
    }
    return false;
  }

  private static int firstIndex(List<ProofControlModels.Quantifier> values, String kind) {
    for (int index = 0; index < values.size(); index++) {
      if (kind.equals(values.get(index).kind())) {
        return index;
      }
    }
    return -1;
  }

  private static int firstNonForall(List<ProofControlModels.Quantifier> values) {
    for (int index = 0; index < values.size(); index++) {
      if (!"forall".equals(values.get(index).kind())) {
        return index;
      }
    }
    return -1;
  }
}
