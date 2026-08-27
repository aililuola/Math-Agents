package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CallLedgerAmbiguousUsageRetentionTest {

  @Test
  void remoteResultUnknownRetainsWorstCaseTokenExposure() {
    CallLedger ledger = new CallLedger(4, 20_000L, new BigDecimal("2.00"));
    CallLedger.Reservation reservation =
        ledger.reserve("proof", "depth", 8_000L, new BigDecimal("0.25"));

    var totals = ledger.commitAmbiguous(reservation.id(), new BigDecimal("0.25"));

    assertThat(totals.calls()).isEqualTo(1L);
    assertThat(totals.totalTokens()).isEqualTo(8_000L);
    assertThat(totals.costUsd()).isEqualByComparingTo("0.25");
  }
}
