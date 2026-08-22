package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.orchestration.BudgetBucket;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopV21BudgetMigrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void legacyUsageMigratesOnceIntoUnclassifiedBudgetAndSurvivesSecondRestore()
      throws Exception {
    Path runDirectory = temporaryDirectory.resolve("v21-budget-run");
    DesktopSolveCheckpoint current;
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v21-budget-run")) {
      current = harness.checkpointRoundTrip();
    }

    ObjectNode json = (ObjectNode) ContractObjectMapper.toTree(current);
    json.put("schemaVersion", 21);
    ObjectNode usage = json.putObject("usageTotals");
    usage.put("calls", 3L);
    usage.put("inputTokens", 1_200L);
    usage.put("outputTokens", 2_400L);
    usage.put("costUsd", new BigDecimal("0.375"));
    usage.put("latencyMs", 15.0d);
    json.remove("budgetDecisions");
    json.remove("budgetEnvelopes");
    json.remove("budgetReservations");
    json.remove("budgetUsage");
    json.remove("pricingSnapshot");
    json.remove("zeroGain");
    json.remove("certifiedGains");
    DesktopSolveCheckpoint version21 =
        ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);

    DesktopSolveCheckpoint firstRestore;
    try (var restored =
        DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v21-budget-run")) {
      restored.restore(version21);
      firstRestore = restored.checkpointRoundTrip();
    }

    assertThat(firstRestore.schemaVersion()).isEqualTo(22);
    assertThat(firstRestore.budgetUsage().committed().calls()).isEqualTo(3L);
    assertThat(firstRestore.budgetUsage().committed().inputTokens()).isEqualTo(1_200L);
    assertThat(firstRestore.budgetUsage().committed().outputTokens()).isEqualTo(2_400L);
    assertThat(firstRestore.budgetUsage().committedByBucket())
        .containsExactlyEntriesOf(
            Map.of(BudgetBucket.LEGACY_UNCLASSIFIED, firstRestore.budgetUsage().committed()));
    assertThat(firstRestore.budgetEnvelopes().envelopes()).isEmpty();
    assertThat(firstRestore.budgetReservations().reservations()).isEmpty();
    assertThat(firstRestore.pricingSnapshot()).isNotNull();

    String firstBudgetHash = budgetHash(firstRestore);
    DesktopSolveCheckpoint secondRestore;
    try (var restoredAgain =
        DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v21-budget-run")) {
      restoredAgain.restore(firstRestore);
      secondRestore = restoredAgain.checkpointRoundTrip();
    }

    assertThat(budgetHash(secondRestore)).isEqualTo(firstBudgetHash);
    assertThat(secondRestore.budgetUsage().committedByBucket())
        .containsOnlyKeys(BudgetBucket.LEGACY_UNCLASSIFIED);
  }

  private static String budgetHash(DesktopSolveCheckpoint checkpoint) {
    return CanonicalJson.stableHash(
        Map.of(
            "decisions", checkpoint.budgetDecisions(),
            "envelopes", checkpoint.budgetEnvelopes(),
            "reservations", checkpoint.budgetReservations(),
            "usage", checkpoint.budgetUsage(),
            "pricing", checkpoint.pricingSnapshot(),
            "zero_gain", checkpoint.zeroGain(),
            "gains", checkpoint.certifiedGains()));
  }
}
