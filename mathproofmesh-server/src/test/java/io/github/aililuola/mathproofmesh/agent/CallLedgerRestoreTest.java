package io.github.aililuola.mathproofmesh.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

final class CallLedgerRestoreTest {
  @Test
  void restoresCommittedUsageWithoutResettingTheRemainingBudget() {
    CallLedger ledger = new CallLedger(3L, 1_000L, BigDecimal.TEN);
    UsageTotals persisted =
        new UsageTotals(2L, 120L, 80L, new BigDecimal("0.25"), 300.0d);

    ledger.restoreCommittedUsage(persisted);

    assertEquals(persisted, ledger.totals());
    assertEquals(1L, ledger.remainingCalls());
    ledger.reserve("review", "verification", 10L, new BigDecimal("0.01"));
    assertThrows(
        BudgetExhaustedError.class,
        () -> ledger.reserve("synthesis", "finalization", 10L, new BigDecimal("0.01")));
  }

  @Test
  void rejectsRestoringUsageIntoANonFreshLedger() {
    CallLedger ledger = new CallLedger(3L, null, BigDecimal.TEN);
    ledger.reserve("triage", "breadth", 10L, BigDecimal.ZERO);

    assertThrows(
        IllegalStateException.class,
        () -> ledger.restoreCommittedUsage(UsageTotals.zero()));
  }
}
