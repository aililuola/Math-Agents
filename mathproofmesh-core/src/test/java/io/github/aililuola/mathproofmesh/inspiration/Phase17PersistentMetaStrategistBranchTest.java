package io.github.aililuola.mathproofmesh.inspiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Phase17PersistentMetaStrategistBranchTest {

  @Test
  void everyObservableTriggerSelectsItsAuditedMechanism() {
    PersistentMetaStrategist strategist =
        new PersistentMetaStrategist(TriggerPolicy.TriggerRules.defaults());

    assertThat(strategist.decide(snapshot(0, false, List.of(), 0, List.of(), false, false))
            .selectedMechanism())
        .isNull();
    assertThat(strategist.decide(snapshot(1, true, List.of(), 0, List.of(), false, false))
            .selectedMechanism())
        .isEqualTo(InspirationMechanism.META_REPLAN);
    assertThat(strategist.decide(snapshot(2, false, List.of("o1"), 0, List.of(), false, false))
            .selectedMechanism())
        .isEqualTo(InspirationMechanism.REVERSE_GOAL_ANALYSIS);
    assertThat(strategist.decide(snapshot(3, false, List.of(), 0.9, List.of(), false, false))
            .selectedMechanism())
        .isEqualTo(InspirationMechanism.REPRESENTATION_SWITCH);
    assertThat(
            strategist
                .decide(snapshot(4, false, List.of(), 0, List.of("same", "same"), false, false))
                .selectedMechanism())
        .isEqualTo(InspirationMechanism.AUXILIARY_CONSTRUCTION);
    assertThat(strategist.decide(snapshot(5, false, List.of(), 0, List.of(), true, false))
            .selectedMechanism())
        .isEqualTo(InspirationMechanism.META_REPLAN);
    assertThat(strategist.decide(snapshot(6, false, List.of(), 0, List.of(), false, true))
            .selectedMechanism())
        .isEqualTo(InspirationMechanism.SURPRISE_EXPLORATION);

    int before = strategist.history().size();
    var duplicate = snapshot(20, false, List.of(), 0, List.of(), false, false);
    strategist.decide(duplicate);
    strategist.decide(duplicate);
    assertThat(strategist.history()).hasSize(before + 1);
  }

  @Test
  void cooldownFallbackCoversSwitchAnalogyConstructionRewriteAndNoAlternative() {
    TriggerPolicy.TriggerRules rules = TriggerPolicy.TriggerRules.defaults();

    PersistentMetaStrategist switchFallback = new PersistentMetaStrategist(rules);
    switchFallback.cool(InspirationMechanism.META_REPLAN, 0, 10);
    assertThat(switchFallback.decide(snapshot(1, true, List.of(), 0, List.of(), false, false))
            .selectedMechanism())
        .isEqualTo(InspirationMechanism.REPRESENTATION_SWITCH);

    PersistentMetaStrategist analogyFallback = new PersistentMetaStrategist(rules);
    analogyFallback.cool(InspirationMechanism.META_REPLAN, 0, 10);
    analogyFallback.cool(InspirationMechanism.REPRESENTATION_SWITCH, 0, 10);
    assertThat(analogyFallback.decide(snapshot(1, true, List.of(), 0, List.of(), false, false))
            .selectedMechanism())
        .isEqualTo(InspirationMechanism.STRUCTURAL_ANALOGY);

    PersistentMetaStrategist constructionFallback = new PersistentMetaStrategist(rules);
    constructionFallback.cool(InspirationMechanism.META_REPLAN, 0, 10);
    constructionFallback.cool(InspirationMechanism.REPRESENTATION_SWITCH, 0, 10);
    constructionFallback.cool(InspirationMechanism.STRUCTURAL_ANALOGY, 0, 10);
    assertThat(
            constructionFallback
                .decide(snapshot(1, true, List.of(), 0, List.of(), false, false))
                .selectedMechanism())
        .isEqualTo(InspirationMechanism.AUXILIARY_CONSTRUCTION);

    PersistentMetaStrategist rewriteFallback = new PersistentMetaStrategist(rules);
    rewriteFallback.cool(InspirationMechanism.REPRESENTATION_SWITCH, 0, 10);
    rewriteFallback.cool(InspirationMechanism.STRUCTURAL_ANALOGY, 0, 10);
    rewriteFallback.cool(InspirationMechanism.AUXILIARY_CONSTRUCTION, 0, 10);
    assertThat(
            rewriteFallback
                .decide(snapshot(1, false, List.of(), 0.9, List.of(), false, false))
                .selectedMechanism())
        .isEqualTo(InspirationMechanism.META_REPLAN);

    PersistentMetaStrategist exhausted = new PersistentMetaStrategist(rules);
    for (InspirationMechanism mechanism :
        List.of(
            InspirationMechanism.META_REPLAN,
            InspirationMechanism.REPRESENTATION_SWITCH,
            InspirationMechanism.STRUCTURAL_ANALOGY,
            InspirationMechanism.AUXILIARY_CONSTRUCTION)) {
      exhausted.cool(mechanism, 0, 10);
    }
    var noAlternative =
        exhausted.decide(snapshot(1, true, List.of(), 0, List.of(), false, false));
    assertThat(noAlternative.selectedMechanism()).isNull();
    assertThat(noAlternative.action()).isEqualTo("continue_current_mechanism");
  }

  @Test
  void constructorAndCooldownCountsFailClosed() {
    assertThatThrownBy(() -> new PersistentMetaStrategist(null))
        .isInstanceOf(NullPointerException.class);
    PersistentMetaStrategist strategist =
        new PersistentMetaStrategist(TriggerPolicy.TriggerRules.defaults());
    assertThatThrownBy(() -> strategist.cool(InspirationMechanism.META_REPLAN, -1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> strategist.cool(InspirationMechanism.META_REPLAN, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static InspirationSnapshot snapshot(
      int round,
      boolean finalRepair,
      List<String> bottlenecks,
      double redundancy,
      List<String> errors,
      boolean stalledDebt,
      boolean allFailed) {
    List<String> active = allFailed ? List.of("r1", "r2") : List.of("r1");
    List<String> failed = allFailed ? active : List.of();
    return new InspirationSnapshot(
        round,
        "problem",
        "number_theory",
        active,
        failed,
        Map.of(),
        0,
        stalledDebt ? Map.of("r1", 2.0d) : Map.of(),
        stalledDebt ? 0.0d : 0.2d,
        List.of(),
        errors,
        redundancy,
        bottlenecks,
        10,
        2,
        1,
        4,
        List.of("o1"),
        Map.of("o1", "lemma"),
        finalRepair,
        false);
  }
}
