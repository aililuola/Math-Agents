package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.IndexScope;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObjectScope;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.Quantifier;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ScopeRelation;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ScopeSignature;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.UniformityScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17ScopeGuardHardeningTest {

  @Test
  void extractionCoversEveryIndexUniformityObjectAndQuantifierMarker() {
    ScopeGuard guard = new ScopeGuard();
    assertThat(guard.extract("eventual", "eventually true", null, 0.0d).indexScope())
        .isEqualTo(IndexScope.EVENTUAL);
    assertThat(guard.extract("prefix", "the first n terms", null, 0.0d).indexScope())
        .isEqualTo(IndexScope.FINITE_PREFIX);
    assertThat(guard.extract("range", "a bounded interval", null, 0.0d).indexScope())
        .isEqualTo(IndexScope.BOUNDED_RANGE);
    assertThat(guard.extract("all", "for every integer", null, 0.0d).indexScope())
        .isEqualTo(IndexScope.ALL);
    assertThat(guard.extract("single", "at n = 4", null, 0.0d).indexScope())
        .isEqualTo(IndexScope.SINGLE_INSTANCE);
    assertThat(guard.extract("unknown", "unmarked", null, -1.0d).indexScope())
        .isEqualTo(IndexScope.UNKNOWN);

    assertThat(guard.extract("uniform", "uniform bound", null, 0.0d).uniformity())
        .isEqualTo(UniformityScope.UNIFORM);
    assertThat(guard.extract("pointwise", "pointwise bound", null, 0.0d).uniformity())
        .isEqualTo(UniformityScope.POINTWISE);
    assertThat(guard.extract("per", "for each n it may depend on n", null, 0.0d).uniformity())
        .isEqualTo(UniformityScope.EXISTS_PER_INSTANCE);
    assertThat(
            guard.extract(
                    "forall-exists",
                    "quantified",
                    List.of(
                        new Quantifier("forall", "integer", 0),
                        new Quantifier("exists", "integer", 1)),
                    0.0d)
                .uniformity())
        .isEqualTo(UniformityScope.EXISTS_PER_INSTANCE);
    assertThat(
            guard.extract(
                    "exists-forall",
                    "quantified",
                    List.of(
                        new Quantifier("exists", "integer", 0),
                        new Quantifier("forall", "integer", 1)),
                    0.0d)
                .uniformity())
        .isEqualTo(UniformityScope.UNIFORM);
    assertThat(
            guard.extract(
                    "only-forall",
                    "quantified",
                    List.of(new Quantifier("forall", "integer", 0)),
                    0.0d)
                .uniformity())
        .isEqualTo(UniformityScope.UNKNOWN);

    assertThat(guard.extract("projection", "projected object", null, 0.0d).objectScope())
        .isEqualTo(ObjectScope.PROJECTION);
    assertThat(guard.extract("quotient", "quotient object", null, 0.0d).objectScope())
        .isEqualTo(ObjectScope.QUOTIENT);
    assertThat(guard.extract("residue", "residue class", null, 0.0d).objectScope())
        .isEqualTo(ObjectScope.RESIDUE_CLASSES);
    assertThat(guard.extract("sub", "subgroup", null, 0.0d).objectScope())
        .isEqualTo(ObjectScope.SUBSTRUCTURE);
    assertThat(guard.extract("full", "entire object", null, 0.0d).objectScope())
        .isEqualTo(ObjectScope.FULL_OBJECT);
    assertThat(guard.extract("cap", "uniform full object for all n", null, 1.0d).confidence())
        .isEqualTo(1.0d);
  }

  @Test
  void comparisonMatrixCoversAllOrderingAndAggregationOutcomes() {
    ScopeGuard guard = new ScopeGuard(0.7d);
    List<ScopeSignature> signatures = new ArrayList<>();
    int id = 0;
    for (IndexScope index : IndexScope.values()) {
      for (UniformityScope uniformity : UniformityScope.values()) {
        for (ObjectScope object : ObjectScope.values()) {
          signatures.add(
              signature(
                  "scope-" + id++,
                  index,
                  uniformity,
                  object,
                  List.of(),
                  1.0d));
        }
      }
    }
    signatures.add(
        signature(
            "quantified-a",
            IndexScope.ALL,
            UniformityScope.UNIFORM,
            ObjectScope.FULL_OBJECT,
            List.of(new Quantifier("forall", "integer", 0)),
            1.0d));
    signatures.add(
        signature(
            "quantified-b",
            IndexScope.ALL,
            UniformityScope.UNIFORM,
            ObjectScope.FULL_OBJECT,
            List.of(new Quantifier("exists", "integer", 0)),
            1.0d));

    java.util.EnumSet<ScopeRelation> observed = java.util.EnumSet.noneOf(ScopeRelation.class);
    for (ScopeSignature premise : signatures) {
      for (ScopeSignature conclusion : signatures) {
        observed.add(guard.compare(premise, conclusion));
      }
    }
    assertThat(observed).containsExactlyInAnyOrder(ScopeRelation.values());

    ScopeSignature unstructured =
        signature(
            "unstructured",
            IndexScope.UNKNOWN,
            UniformityScope.UNKNOWN,
            ObjectScope.UNKNOWN,
            List.of(),
            0.1d);
    assertThat(guard.canClose(unstructured, unstructured)).isTrue();
    ScopeSignature structuredLow =
        signature(
            "structured-low",
            IndexScope.ALL,
            UniformityScope.UNIFORM,
            ObjectScope.FULL_OBJECT,
            List.of(),
            0.6d);
    ScopeSignature structuredHigh =
        signature(
            "structured-high",
            IndexScope.ALL,
            UniformityScope.UNIFORM,
            ObjectScope.FULL_OBJECT,
            List.of(),
            0.8d);
    assertThat(guard.canClose(structuredLow, structuredHigh)).isFalse();
    assertThat(guard.canClose(structuredHigh, structuredLow)).isTrue();
    assertThat(guard.canPromoteFact(structuredLow)).isFalse();
    assertThat(guard.canPromoteFact(structuredHigh)).isTrue();
  }

  private static ScopeSignature signature(
      String id,
      IndexScope index,
      UniformityScope uniformity,
      ObjectScope object,
      List<Quantifier> quantifiers,
      double confidence) {
    return new ScopeSignature(
        id, index, uniformity, object, quantifiers, List.of(), List.of(), confidence);
  }
}
