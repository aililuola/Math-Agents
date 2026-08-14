package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeferredExpansionLedgerLifecycleTest {
  @Test
  void terminalTransitionsAreMonotonicIdempotentAndConflictClosed() {
    DeferredExpansionLedger reactivated = ledger("reactivate");
    String reactivatedId = reactivated.records().getFirst().deferredId();
    DeferredExpansionRecord first =
        reactivated.markReactivated(reactivatedId, 4, "CAPACITY_RELEASED", "task-1");
    assertThat(first.status()).isEqualTo(DeferredExpansionStatus.REACTIVATED);
    assertThat(reactivated.markReactivated(reactivatedId, 4, "CAPACITY_RELEASED", "task-1"))
        .isSameAs(first);
    assertThatThrownBy(() -> reactivated.markRetired(reactivatedId, 5, "conflict"))
        .isInstanceOf(IllegalStateException.class);

    DeferredExpansionLedger satisfied = ledger("satisfied");
    String satisfiedId = satisfied.records().getFirst().deferredId();
    assertThat(
            satisfied
                .markSatisfiedByActiveTarget(satisfiedId, 3, "CANONICAL_TARGET_ALREADY_ACTIVE")
                .status())
        .isEqualTo(DeferredExpansionStatus.SATISFIED_BY_ACTIVE_TARGET);

    DeferredExpansionLedger retired = ledger("retired");
    String retiredId = retired.records().getFirst().deferredId();
    DeferredExpansionRecord terminal = retired.markRetired(retiredId, 6, "TARGET_TERMINAL");
    assertThat(terminal.status()).isEqualTo(DeferredExpansionStatus.RETIRED);
    assertThat(retired.markRetired(retiredId, 6, "TARGET_TERMINAL")).isSameAs(terminal);
    assertThat(retired.activeDeferredRecords()).isEmpty();
  }

  private static DeferredExpansionLedger ledger(String suffix) {
    DeferredExpansionLedger ledger = new DeferredExpansionLedger();
    ledger.record(
        "problem",
        1,
        "route-" + suffix,
        "obligation-" + suffix,
        "canonical-" + suffix,
        FocusedRecoveryActionType.NEW_STRATEGY,
        FocusedExpansionDecision.deferCapacity());
    return ledger;
  }
}
