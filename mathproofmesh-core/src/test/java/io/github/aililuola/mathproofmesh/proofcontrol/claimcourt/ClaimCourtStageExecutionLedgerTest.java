package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimCourtStageExecutionLedgerTest {
  @Test
  void durableResultRollsForwardWithoutProviderReplay() {
    ClaimCourtStageExecutionLedger ledger = new ClaimCourtStageExecutionLedger();
    var reserved =
        ledger.reserve(
            "case-1", ClaimCourtStage.PROOF_AUDIT, List.of("claim-1"), "input-hash", "auditor");
    ledger.start(reserved.executionId());
    ledger.recordResult(
        reserved.executionId(),
        JsonNodeFactory.instance.objectNode().put("verdict", "valid"));
    ClaimCourtStageExecutionLedger restored = new ClaimCourtStageExecutionLedger();
    restored.restore(ledger.snapshot());
    assertThat(restored.complete(reserved.executionId()).status())
        .isEqualTo(ClaimCourtStageExecutionStatus.COMPLETED);
    assertThat(restored.records()).hasSize(1);
  }
}
