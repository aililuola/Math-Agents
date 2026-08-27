package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17NearMissBranchTest {

  @Test
  void rejectionPreconditionsAreIndependentlyFailClosed() {
    NearMissLedger ledger = new NearMissLedger();
    assertThat(ledger.record(candidate("realizer", "idea", List.of("failed"), List.of("part")), false))
        .isNull();
    assertThat(ledger.record(candidate("realizer", "idea", List.of(), List.of("part")), true))
        .isNull();
    assertThat(ledger.record(candidate("realizer", "idea", List.of("failed"), List.of()), true))
        .isNull();
    assertThat(ledger.record(candidate("realizer", " ", List.of("failed"), List.of("part")), true))
        .isNull();
  }

  @Test
  void eachFailureTaxonomySelectsItsBoundedRepairOperator() {
    NearMissLedger ledger = new NearMissLedger();
    var realizer =
        ledger.record(candidate("realizer admissibility", "idea-r", List.of("f"), List.of("p")), true);
    var induction =
        ledger.record(candidate("induction occurrence", "idea-i", List.of("f"), List.of("p")), true);
    var scope =
        ledger.record(candidate("scope goal", "idea-s", List.of("f"), List.of("p")), true);
    var bridge =
        ledger.record(candidate("bridge failure", "idea-b", List.of("f"), List.of("p")), true);
    var local =
        ledger.record(candidate("other", "idea-l", List.of("f"), List.of("p")), true);

    assertThat(realizer.repairModule()).isEqualTo("realizer_repair");
    assertThat(induction.repairModule()).isEqualTo("induction_selector");
    assertThat(induction.suggestedInductionMeasures()).containsExactly("occurrence_count");
    assertThat(scope.suggestedRepairOperators()).containsExactly("weaken_target", "repair_scope");
    assertThat(bridge.repairModule()).isEqualTo("minimal_bridge");
    assertThat(local.repairModule()).isEqualTo("bounded_local_repair");
    assertThat(ledger.promptHint(local))
        .contains("NON_AUTHORITATIVE_NEAR_MISS", "idea-l", "failed");
    assertThat(ledger.relevant("route")).hasSize(5).isSortedAccordingTo(
        java.util.Comparator.comparing(NearMissLedger.NearMiss::id));
    assertThat(ledger.relevant("other")).isEmpty();

    var duplicate =
        ledger.record(candidate("other", "idea-l", List.of("f"), List.of("p")), true);
    assertThat(duplicate).isSameAs(local);
  }

  @Test
  void repairRequiresKnownRecordAndEvidence() {
    NearMissLedger ledger = new NearMissLedger();
    var record =
        ledger.record(candidate("bridge", "idea", List.of("failed"), List.of("part")), true);
    assertThatThrownBy(() -> ledger.markRepaired(record.id(), " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ledger.markRepaired("missing", "evidence"))
        .isInstanceOf(NullPointerException.class);
    assertThat(ledger.markRepaired(record.id(), "evidence").repaired()).isTrue();
  }

  private static NearMissLedger.Candidate candidate(
      String failure, String idea, List<String> failed, List<String> salvageable) {
    return new NearMissLedger.Candidate(
        "route",
        "obligation",
        "target",
        idea,
        "candidate",
        List.of("preserved"),
        failed,
        failure,
        salvageable,
        List.of("review"),
        0.8d);
  }
}
