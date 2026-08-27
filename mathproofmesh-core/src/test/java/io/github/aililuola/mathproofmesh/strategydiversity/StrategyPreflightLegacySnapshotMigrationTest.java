package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyPreflightLegacySnapshotMigrationTest {
  @Test
  void legacyStartedAndCompletedRecordsAcquireConservativeTypedFrontiers() {
    CriticalClaimPreflightEvidence evidence =
        new CriticalClaimPreflightEvidence(
            CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE,
            "legacy-computation",
            List.of("artifact://legacy-result"),
            "legacy result");
    StrategyPreflightExecutionRecord legacyCompleted =
        legacy("completed", evidence, 1, 1);
    StrategyPreflightExecutionRecord legacyStarted =
        legacy("started", null, null, 1);

    StrategyPreflightExecutionRecord completed = readLegacy(legacyCompleted, "completed");
    StrategyPreflightExecutionRecord running = readLegacy(legacyStarted, "started");

    assertThat(completed.status()).isEqualTo(StrategyPreflightExecutionStatus.COMPLETED);
    assertThat(completed.resultArtifactRef()).isEqualTo("artifact://legacy-result");
    assertThat(completed.replayHash()).isNotBlank();
    assertThat(running.status()).isEqualTo(StrategyPreflightExecutionStatus.RUNNING);
    assertThat(running.resultDurable()).isFalse();
  }

  private static StrategyPreflightExecutionRecord legacy(
      String state,
      CriticalClaimPreflightEvidence evidence,
      Integer completedRound,
      int executionCount) {
    return new StrategyPreflightExecutionRecord(
        "legacy-execution",
        "legacy-problem",
        "legacy-strategy",
        "legacy-claim",
        "legacy-plan",
        state,
        evidence,
        0,
        completedRound,
        executionCount,
        1L);
  }

  private static StrategyPreflightExecutionRecord readLegacy(
      StrategyPreflightExecutionRecord source, String state) {
    ObjectNode json = (ObjectNode) ContractObjectMapper.toTree(source);
    json.remove("actionKey");
    json.remove("typedInputHash");
    json.remove("resultArtifactRef");
    json.remove("replayHash");
    json.remove("resultRound");
    json.put("state", state);
    return ContractObjectMapper.read(json, StrategyPreflightExecutionRecord.class);
  }
}
