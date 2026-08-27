package io.github.aililuola.mathproofmesh.inspiration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.InspirationContextMode;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.InspirationTriggerType;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InspirationOutcomeLearningParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_outcome_reward_guides_selection_without_becoming_evidence",
        "test_minimum_exploration_keeps_untried_mechanisms_eligible");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("InspirationOutcomeLearningParityTest", authorityFunction);
  }

  @Test
  void consecutiveMaterializedNoGainRoundsTriggerMechanismCooldown() {
    InspirationOutcomeLedger ledger =
        new InspirationOutcomeLedger(
            InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE).adaptive());
    for (int round = 1; round <= 2; round++) {
      InspirationProposal proposal = proposal("proposal-" + round);
      ledger.register(
          proposal,
          snapshot(round),
          InspirationTriggerType.STAGNATION,
          List.of(ObligationKind.SUBGOAL),
          4.0d,
          List.of("route-1"),
          List.of("obligation-1"));
      ledger.recordMaterialization(proposal.proposalId(), "scheduled_route_task", false);
    }

    assertThat(
            ledger.inNoGainCooldown(
                InspirationMechanism.REPRESENTATION_SWITCH, 3, 2, 2))
        .isTrue();
    assertThat(
            ledger.inNoGainCooldown(
                InspirationMechanism.REPRESENTATION_SWITCH, 5, 2, 2))
        .isFalse();
  }

  private static InspirationProposal proposal(String id) {
    return new InspirationProposal(
        null,
        null,
        null,
        InspirationContextMode.LOCAL,
        1,
        EvidenceType.UNVERIFIED_IDEA,
        0.8d,
        List.of("obligation-1"),
        null,
        InspirationMechanism.REPRESENTATION_SWITCH,
        null,
        0.9d,
        new NoveltySignature(),
        id,
        0,
        "materialize one bounded bridge task",
        null,
        null,
        "inspiration-author",
        "prove the selected bridge obligation",
        List.of("route-1"),
        "task-" + id,
        "trigger-" + id);
  }

  private static InspirationSnapshot snapshot(int round) {
    return new InspirationSnapshot(
        round,
        "problem-hash",
        "number_theory",
        List.of("route-1"),
        List.of(),
        Map.of("route-1", round),
        0,
        Map.of("route-1", 4.0d),
        0.0d,
        List.of(4.0d, 4.0d),
        List.of(),
        0.0d,
        List.of("obligation-1"),
        20,
        2,
        1,
        12,
        List.of("obligation-1"),
        Map.of("obligation-1", "subgoal"),
        false,
        false);
  }
}
