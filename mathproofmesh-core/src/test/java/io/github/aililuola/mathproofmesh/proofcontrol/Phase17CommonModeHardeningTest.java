package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.Assumption;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.AssumptionDomain;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.AssumptionFamily;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase17CommonModeHardeningTest {

  @Test
  void analysisFiltersDomainsFrozenRoutesAndBuildsWordingAndTypedFamilies() {
    CommonModeAnalyzer analyzer = new CommonModeAnalyzer();
    Assumption first = assumption("a1", "Assume the invariant is monotone", "r1", false, Set.of("d1"), 1.0d);
    Assumption second = assumption("a2", "the invariant is monotone", "r2", false, Set.of("d1"), 1.0d);
    Assumption verified = assumption("a3", "using the invariant is monotone", "r3", true, Set.of("d1"), 0.5d);
    Assumption nonMath =
        new Assumption("process", "workflow state", Set.of("r1"), AssumptionDomain.PROCESS,
            false, Set.of(), 1.0d);
    Assumption frozen = assumption("frozen", "the invariant is monotone", "frozen", false, Set.of(), 1.0d);
    Map<String, Set<String>> graph =
        Map.of("d1", Set.of("d2"), "d2", Set.of("d3"), "d3", Set.of());
    List<AssumptionFamily> families =
        analyzer.analyze(
            List.of(first, second, verified, nonMath, frozen),
            Set.of("r1", "r2", "r3", "frozen"),
            Set.of("frozen"),
            graph);
    assertThat(families).isNotEmpty();
    assertThat(families)
        .anySatisfy(
            family -> {
              assertThat(family.liveRouteIds()).contains("r1", "r2", "r3");
              assertThat(family.typedDependencyClosure()).contains("d1", "d2", "d3");
              assertThat(family.commonModeRisk()).isZero();
            });

    List<AssumptionFamily> risky =
        analyzer.analyze(
            List.of(
                assumption("x1", "the field is finite", "r1", false, Set.of(), 1.0d),
                assumption("x2", "the field is finite", "r2", false, Set.of(), 1.0d)),
            Set.of("r1", "r2", "r3"),
            null,
            null);
    assertThat(risky)
        .singleElement()
        .satisfies(
            family -> {
              assertThat(family.commonModeRisk()).isBetween(0.0d, 1.0d);
              assertThat(family.dependencyCutset()).isFalse();
            });
    assertThat(
            analyzer.analyze(
                List.of(first), Set.of("r1"), Set.of(), graph))
        .isEmpty();
    assertThat(
            analyzer.analyze(
                List.of(nonMath), Set.of("r1"), Set.of(), graph))
        .isEmpty();
  }

  @Test
  void challengerReviewIndependenceStrategyAndHardStopBranchesAreClosed() {
    CommonModeAnalyzer analyzer = new CommonModeAnalyzer();
    AssumptionFamily risky =
        new AssumptionFamily(
            "family",
            "the invariant is monotone",
            List.of("a1", "a2"),
            Set.of("r1", "r2"),
            Set.of("d1"),
            0.9d,
            true);
    var task = analyzer.challengerForFamily(risky);
    assertThat(analyzer.challengerForFamily(risky)).isEqualTo(task);
    assertThat(analyzer.blocksHardStop(risky)).isTrue();
    for (String reviewer : new String[] {null, "", " ", "challenger"}) {
      assertThatThrownBy(
              () ->
                  analyzer.reviewChallenge(
                      task,
                      "challenger",
                      reviewer,
                      CommonModeAnalyzer.ChallengeOutcome.INCONCLUSIVE,
                      true,
                      List.of("e"),
                      "detail"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                analyzer.reviewChallenge(
                    task, "challenger", "reviewer",
                    CommonModeAnalyzer.ChallengeOutcome.BLOCKED, false, List.of("e"), "detail"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                analyzer.reviewChallenge(
                    task, "challenger", "reviewer",
                    CommonModeAnalyzer.ChallengeOutcome.BLOCKED, true, null, "detail"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                analyzer.reviewChallenge(
                    task, "challenger", "reviewer",
                    CommonModeAnalyzer.ChallengeOutcome.BLOCKED, true, List.of(), "detail"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                analyzer.reviewChallenge(
                    task, "challenger", "reviewer",
                    CommonModeAnalyzer.ChallengeOutcome.BLOCKED, true, List.of("e"), " "))
        .isInstanceOf(IllegalArgumentException.class);

    var review =
        analyzer.reviewChallenge(
            task,
            "challenger",
            "reviewer",
            CommonModeAnalyzer.ChallengeOutcome.REFUTED,
            true,
            List.of("e2", "e1", "e1"),
            "audited");
    assertThat(review.evidenceRefs()).containsExactly("e1", "e2");
    assertThat(review.mayCloseGoal()).isFalse();
    assertThat(analyzer.blocksHardStop(risky)).isFalse();
    assertThat(
            analyzer.reviewChallenge(
                task, "other", "another",
                CommonModeAnalyzer.ChallengeOutcome.VERIFIED, true, List.of("new"), "new"))
        .isEqualTo(review);

    AssumptionFamily verified =
        new AssumptionFamily("verified", "statement", List.of(), Set.of(), Set.of(), 0.0d, false);
    assertThat(analyzer.blocksHardStop(verified)).isFalse();
    assertThat(analyzer.strategyIndependent(risky, Set.of("d1"), "different")).isFalse();
    assertThat(analyzer.strategyIndependent(risky, Set.of("other"), "Assume the invariant is monotone"))
        .isFalse();
    assertThat(analyzer.strategyIndependent(risky, Set.of("other"), "an unrelated construction"))
        .isTrue();
  }

  @Test
  void canonicalizationSemanticPolarityAndTransitiveClosureCoverBoundaries() {
    CommonModeAnalyzer analyzer = new CommonModeAnalyzer();
    assertThat(
            analyzer.familyKey(
                "[TYPE:x][STATUS:y][SOURCE:z][PREMISE_ELIGIBLE:true] assume that the A, invariant!"))
        .isEqualTo("invariant");
    assertThat(analyzer.semanticallyMatch("Assume the invariant is monotone", "the invariant is monotone"))
        .isTrue();
    assertThat(analyzer.semanticallyMatch("the claim is true", "the claim is not true"))
        .isFalse();
    assertThat(analyzer.semanticallyMatch("integer parity", "unrelated geometry")).isFalse();

    assertThat(CommonModeAnalyzer.transitiveClosure(null, null)).isEmpty();
    assertThat(CommonModeAnalyzer.transitiveClosure(Set.of("a"), null)).containsExactly("a");
    assertThat(
            CommonModeAnalyzer.transitiveClosure(
                Set.of("a"), Map.of("a", Set.of("b"), "b", Set.of("a", "c"))))
        .containsExactlyInAnyOrder("a", "b", "c");
  }

  private static Assumption assumption(
      String id,
      String statement,
      String route,
      boolean verified,
      Set<String> dependencies,
      double load) {
    return new Assumption(
        id,
        statement,
        Set.of(route),
        AssumptionDomain.MATHEMATICAL,
        verified,
        dependencies,
        load);
  }
}
