package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StrategyCandidateLedgerSnapshotTest {
  @Test
  void candidateStateSurvivesRestoreWithoutRegression() {
    StrategyCandidateLedger ledger = new StrategyCandidateLedger();
    ledger.capture("episode", "candidate", 0, false);
    ledger.transition(
        "candidate",
        StrategyCandidateStatus.PREFLIGHTED,
        "mechanism-hash",
        "preflight-hash",
        0.4d,
        "ready");
    String before = ledger.ledgerHash();

    StrategyCandidateLedger restored = StrategyCandidateLedger.restore(ledger.snapshot());

    assertThat(restored.ledgerHash()).isEqualTo(before);
    assertThat(restored.snapshot().records().get("candidate").status())
        .isEqualTo(StrategyCandidateStatus.PREFLIGHTED);
  }
}
