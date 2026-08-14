package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProofTaskControlActionClassificationTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void sourceTokensUseExplicitStablePriority(
      String source, boolean hasFamily, FocusedRecoveryActionType expected) {
    assertThat(FocusedRecoveryActionType.classifyTaskSource(source, hasFamily))
        .isEqualTo(expected);
  }

  private static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of("exact-falsification:claim", true, FocusedRecoveryActionType.EXACT_FALSIFICATION),
        Arguments.of("focused-skeptic:review", true, FocusedRecoveryActionType.FOCUSED_SKEPTIC),
        Arguments.of("meta-review:route", true, FocusedRecoveryActionType.FOCUSED_SKEPTIC),
        Arguments.of("focused-recovery:episode", true, FocusedRecoveryActionType.FOCUSED_PROVER),
        Arguments.of("proof-debt-stall:route", true, FocusedRecoveryActionType.FOCUSED_PROVER),
        Arguments.of("family-bridge:repair", true, FocusedRecoveryActionType.FAMILY_BRIDGE_REPAIR),
        Arguments.of("bridge:repair", true, FocusedRecoveryActionType.FAMILY_BRIDGE_REPAIR),
        Arguments.of("unscoped-bridge:repair", false, FocusedRecoveryActionType.UNSCOPED_BRIDGE),
        Arguments.of("unscoped-bridge:repair", true, FocusedRecoveryActionType.UNSCOPED_BRIDGE),
        Arguments.of("generic-inspiration:seed", true, FocusedRecoveryActionType.GENERIC_INSPIRATION),
        Arguments.of("representation-switch:seed", true, FocusedRecoveryActionType.REPRESENTATION_SWITCH),
        Arguments.of("structural-analogy:seed", true, FocusedRecoveryActionType.STRUCTURAL_ANALOGY),
        Arguments.of("verified-claim-reuse:fact", true, FocusedRecoveryActionType.VERIFIED_CLAIM_REUSE),
        Arguments.of("new-strategy:seed", true, FocusedRecoveryActionType.NEW_STRATEGY),
        Arguments.of(
            "focused-skeptic:exact-falsification",
            true,
            FocusedRecoveryActionType.EXACT_FALSIFICATION),
        Arguments.of(
            "focused-skeptic:exact-looking-review",
            true,
            FocusedRecoveryActionType.FOCUSED_SKEPTIC));
  }
}
