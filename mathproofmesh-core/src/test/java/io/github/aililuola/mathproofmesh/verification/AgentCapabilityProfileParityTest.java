package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentCapabilityProfileParityTest {

  @Test
  void capability_is_separate_by_domain_and_role_and_ignores_self_report() {
    AgentCapabilityProfile profile =
        new AgentCapabilityProfile(
            new AgentCapabilityProfile.Settings(1, 0.95, 0.35, 0.25, 0.20, 0.20));

    CapabilityCell cell =
        profile.update(
            "reviewer",
            "number_theory",
            "detailed_verifier",
            CapabilityObservationKind.TOOL_AGREEMENT,
            true,
            0.01);

    assertThat(cell.score()).isEqualTo(1.0);
    assertThat(profile.ignoredSelfReports()).isEqualTo(1);
    assertThat(profile.score("reviewer", "geometry", "detailed_verifier"))
        .isEqualTo(0.5);
    assertThat(profile.score("reviewer", "number_theory", "prover"))
        .isEqualTo(0.5);
  }

  @Test
  void accepting_mutated_false_proof_lowers_verifier_capability() {
    AgentCapabilityProfile profile =
        new AgentCapabilityProfile(
            new AgentCapabilityProfile.Settings(1, 0.95, 0.35, 0.25, 0.20, 0.20));
    ProofMutationHarness harness = new ProofMutationHarness();
    java.util.List<ProofMutation> mutations =
        java.util.Arrays.stream(MutationKind.values())
            .map(
                kind ->
                    harness.mutate(
                        VerificationFixtures.step(
                            "s1", "For every n, a_n <= b_n."),
                        kind))
            .toList();
    assertThat(mutations)
        .extracting(ProofMutation::kind)
        .containsExactlyInAnyOrder(MutationKind.values());
    assertThat(mutations)
        .extracting(ProofMutation::mutatedStatement)
        .allMatch(statement -> !"For every n, a_n <= b_n.".equals(statement));
    ProofMutation mutation =
        mutations.stream()
            .filter(item -> item.kind() == MutationKind.ALTER_SIGN)
            .findFirst()
            .orElseThrow();

    harness.record(
        new MutationResult(mutation.mutationId(), "reviewer", false, false),
        "inequalities",
        "detailed_verifier",
        profile);

    assertThat(profile.score("reviewer", "inequalities", "detailed_verifier"))
        .isZero();
  }

  @Test
  void dispatch_uses_domain_and_role_capability() {
    AgentCapabilityProfile profile =
        new AgentCapabilityProfile(
            new AgentCapabilityProfile.Settings(1, 0.95, 0.35, 0.25, 0.20, 0.20));
    for (String role : java.util.List.of("prover", "representation_switchboard")) {
      profile.update(
          "explorer-a",
          "number_theory",
          role,
          CapabilityObservationKind.RECENT_TASK,
          false,
          null);
      profile.update(
          "explorer-b",
          "number_theory",
          role,
          CapabilityObservationKind.RECENT_TASK,
          true,
          null);
      assertThat(
              profile.selectBest(
                  java.util.List.of("explorer-a", "explorer-b"),
                  "number_theory",
                  role))
          .isEqualTo("explorer-b");
    }
  }
}
