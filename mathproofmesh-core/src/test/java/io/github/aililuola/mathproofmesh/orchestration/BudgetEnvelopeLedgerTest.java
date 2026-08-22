package io.github.aililuola.mathproofmesh.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BudgetEnvelopeLedgerTest {

  @Test
  void concurrentActionsCannotSpendFinishReserveAndSettlementIsExactlyOnce() {
    BudgetEnvelopeLedger ledger =
        new BudgetEnvelopeLedger(vector(10, 100_000, 100_000, 200_000, "2"),
            vector(3, 20_000, 30_000, 50_000, "0.5"));
    BudgetEnvelope first =
        ledger.reserve("run", "epoch", "work-a", "decision-a", BudgetBucket.DEPTH,
            vector(4, 20_000, 30_000, 50_000, "0.4"));
    ledger.activate(first.envelopeId());

    assertThatThrownBy(
            () ->
                ledger.reserve(
                    "run",
                    "epoch",
                    "work-b",
                    "decision-b",
                    BudgetBucket.BREADTH,
                    vector(4, 20_000, 30_000, 50_000, "0.4")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ACTION_BUDGET_ENVELOPE_EXHAUSTED");

    BudgetPhysicalReservation child =
        ledger.reservePhysical(
            first.envelopeId(),
            "call-1",
            "key-1",
            "proof",
            0,
            "pricing",
            vector(1, 2_000, 4_000, 6_000, "0.05"));
    ledger.markDispatched(child.reservationId());
    BudgetPhysicalReservation settled =
        ledger.settle(child.reservationId(), vector(1, 1_500, 3_000, 4_500, "0.04"));

    assertThat(
            ledger.settle(
                child.reservationId(), vector(1, 1_500, 3_000, 4_500, "0.04")))
        .isEqualTo(settled);
    assertThatThrownBy(
            () ->
                ledger.settle(
                    child.reservationId(), vector(1, 1_600, 3_000, 4_600, "0.04")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(ledger.usageSnapshot().committed().calls()).isEqualTo(1L);
    assertThat(ledger.reservedResources().calls()).isEqualTo(3L);
    assertThat(ledger.available().calls()).isEqualTo(6L);
  }

  @Test
  void dispatchedUnknownUsageRemainsQuarantinedAcrossSnapshots() {
    BudgetEnvelopeLedger ledger =
        new BudgetEnvelopeLedger(vector(5, 50_000, 50_000, 100_000, "1"),
            BudgetResourceVector.zero());
    BudgetEnvelope envelope =
        ledger.reserve("run", "epoch", "work", "decision", BudgetBucket.DEPTH,
            vector(2, 10_000, 20_000, 30_000, "0.3"));
    ledger.activate(envelope.envelopeId());
    BudgetPhysicalReservation child =
        ledger.reservePhysical(
            envelope.envelopeId(), "call", "key", "proof", 0, "pricing",
            vector(1, 5_000, 10_000, 15_000, "0.15"));
    ledger.markDispatched(child.reservationId());

    ledger.quarantineUncertain(child.reservationId());

    assertThat(ledger.envelopeSnapshot().envelopes())
        .singleElement()
        .extracting(BudgetEnvelope::status)
        .isEqualTo(BudgetEnvelopeStatus.QUARANTINED_UNCERTAIN);
    assertThat(ledger.reservedResources()).isEqualTo(BudgetResourceVector.zero());
    assertThat(ledger.available().calls()).isEqualTo(4L);
  }

  private static BudgetResourceVector vector(
      long calls, long input, long output, long total, String cost) {
    return new BudgetResourceVector(calls, input, output, total, new BigDecimal(cost));
  }
}
