package io.github.aililuola.mathproofmesh.inspiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Phase17NoveltyGateHardeningTest {

  @Test
  void emptyObligationOnlyAndFullyStructuralSimilaritiesCoverEveryWeightBranch() {
    NoveltyGate gate =
        new NoveltyGate(InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE).novelty());
    assertThatThrownBy(() -> new NoveltyGate(null)).isInstanceOf(NullPointerException.class);

    NoveltySignature empty = signature(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    assertThat(gate.similarity(empty, empty).score()).isZero();
    assertThat(gate.assess(empty, null))
        .satisfies(
            assessment -> {
              assertThat(assessment.noveltyScore()).isEqualTo(1.0d);
              assertThat(assessment.nearestHash()).isNull();
              assertThat(assessment.duplicate()).isFalse();
            });

    NoveltySignature obligationOnly =
        signature(List.of(), List.of(), List.of(), List.of(), List.of(), List.of("o1"));
    assertThat(gate.similarity(obligationOnly, obligationOnly).score()).isEqualTo(0.5d);

    NoveltySignature full =
        signature(
            Arrays.asList(" Graph ", "graph"),
            List.of(" Modular "),
            List.of(" Bridge Lemma "),
            List.of(" Quotient "),
            List.of(" Parity "),
            List.of(" obligation "));
    NoveltySignature same =
        signature(
            List.of("graph"),
            List.of("modular"),
            List.of("bridge_lemma"),
            List.of("quotient"),
            List.of("parity"),
            List.of("obligation"));
    var similarity = gate.similarity(full, same);
    assertThat(similarity.score()).isEqualTo(1.0d);
    assertThat(similarity.dimensions())
        .containsKeys(
            "representation",
            "mechanism",
            "object",
            "transformation",
            "principle",
            "obligation",
            "extension");
    assertThat(gate.assess(full, List.of(same)).duplicate()).isTrue();
  }

  @Test
  void assessChoosesNearestOnTiesAndDistinguishesChainStructuralAndNovelCases() {
    NoveltyGate gate =
        new NoveltyGate(InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE).novelty());
    NoveltySignature candidate =
        signature(
            List.of("integer"),
            List.of("graph"),
            List.of("induction"),
            List.of("reduce"),
            List.of("invariant"),
            List.of("o1"));
    NoveltySignature chainDuplicate =
        signature(
            List.of("different"),
            List.of("graph"),
            List.of("induction"),
            List.of("reduce"),
            List.of("invariant"),
            List.of("other"));
    var chain = gate.assess(candidate, List.of(chainDuplicate));
    assertThat(chain.duplicate()).isTrue();
    assertThat(chain.mechanismChainSimilarity()).isPositive();

    NoveltySignature structural =
        signature(
            List.of("integer"),
            List.of("graph"),
            List.of("different"),
            List.of(),
            List.of(),
            List.of("o1"));
    assertThat(gate.assess(candidate, List.of(structural)).maximumSimilarity()).isPositive();

    NoveltySignature novel =
        signature(
            List.of("manifold"),
            List.of("coordinates"),
            List.of("compactness"),
            List.of("cover"),
            List.of("topology"),
            List.of("o9"));
    var result = gate.assess(candidate, List.of(novel, novel));
    assertThat(result.duplicate()).isFalse();
    assertThat(result.nearestHash()).isNotNull();
    assertThat(result.noveltyScore()).isBetween(0.0d, 1.0d);
  }

  @Test
  void chainEarlyReturnsAndSharedDimensionCountersCoverEachMissingDimension() {
    NoveltyGate gate =
        new NoveltyGate(InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE).novelty());
    NoveltySignature baseline =
        signature(
            List.of("object"),
            List.of("representation"),
            List.of("mechanism"),
            List.of("transformation"),
            List.of("principle"),
            List.of("obligation"));
    List<NoveltySignature> missing =
        List.of(
            signature(List.of("object"), List.of(), List.of("mechanism"), List.of("transformation"), List.of("principle"), List.of()),
            signature(List.of("object"), List.of("representation"), List.of("mechanism"), List.of(), List.of("principle"), List.of()),
            signature(List.of("object"), List.of("representation"), List.of("mechanism"), List.of("transformation"), List.of(), List.of()));
    for (NoveltySignature value : missing) {
      assertThat(gate.assess(baseline, List.of(value)).mechanismChainSimilarity()).isZero();
    }

    NoveltySignature disjoint =
        signature(
            List.of("other-object"),
            List.of("other-representation"),
            List.of("other-mechanism"),
            List.of("other-transformation"),
            List.of("other-principle"),
            List.of());
    assertThat(gate.similarity(baseline, disjoint).score()).isZero();
    assertThat(
            gate.similarity(
                    signature(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                    signature(List.of("x"), List.of(), List.of(), List.of(), List.of(), List.of()))
                .score())
        .isZero();
  }

  private static NoveltySignature signature(
      List<String> objects,
      List<String> representations,
      List<String> mechanisms,
      List<String> transformations,
      List<String> principles,
      List<String> obligations) {
    return new NoveltySignature(
        objects,
        List.of(),
        transformations,
        mechanisms,
        null,
        null,
        null,
        principles,
        Map.of(),
        representations,
        obligations);
  }
}
