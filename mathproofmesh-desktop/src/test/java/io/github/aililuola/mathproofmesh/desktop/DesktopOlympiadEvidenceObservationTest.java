package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopOlympiadEvidenceObservationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void reportsAnOverrunEvenWhenBudgetAndPricingProjectionsExist() {
    ObjectNode state = completeBudgetState();
    ArrayNode envelopes = state.path("budgetEnvelopes").path("envelopes").deepCopy();
    envelopes.addObject().put("status", "OVERRUN");
    state.withObject("budgetEnvelopes").set("envelopes", envelopes);

    assertEquals(1, DesktopOlympiadEvidenceExporter.budgetViolations(state));
  }

  @Test
  void acceptsCompleteBudgetEvidenceWithoutAnOverrun() {
    assertEquals(0, DesktopOlympiadEvidenceExporter.budgetViolations(completeBudgetState()));
  }

  @Test
  void failsClosedWhenBudgetEvidenceIsMissing() {
    assertEquals(
        3,
        DesktopOlympiadEvidenceExporter.budgetViolations(
            JsonNodeFactory.instance.objectNode()));
  }

  @Test
  void acceptsTerminalUsageThatMonotonicallyExtendsTheSemanticCheckpointPrefix() {
    ObjectNode state = completeBudgetState();
    state
        .withObject("usageTotals")
        .put("calls", 1L)
        .put("inputTokens", 7L)
        .put("outputTokens", 11L)
        .put("costUsd", 0.01d);
    state
        .withObject("budgetUsage")
        .withObject("committed")
        .put("calls", 1L)
        .put("inputTokens", 7L)
        .put("outputTokens", 11L)
        .put("costUsd", 0.01d);

    assertEquals(
        0,
        DesktopOlympiadEvidenceExporter.budgetViolations(
            state,
            new RunExecutionBackend.ExecutionUsage(
                2L, 14L, 22L, new BigDecimal("0.02"), 2.0d)));
  }

  @Test
  void rejectsTerminalUsageThatFallsBehindTheSemanticCheckpointPrefix() {
    ObjectNode state = completeBudgetState();
    state
        .withObject("usageTotals")
        .put("calls", 2L)
        .put("inputTokens", 14L)
        .put("outputTokens", 22L)
        .put("costUsd", 0.02d);
    state
        .withObject("budgetUsage")
        .withObject("committed")
        .put("calls", 2L)
        .put("inputTokens", 14L)
        .put("outputTokens", 22L)
        .put("costUsd", 0.02d);

    assertEquals(
        1,
        DesktopOlympiadEvidenceExporter.budgetViolations(
            state,
            new RunExecutionBackend.ExecutionUsage(
                1L, 7L, 11L, new BigDecimal("0.01"), 1.0d)));
  }

  @Test
  void rejectsAPostCheckpointUsageDeltaWithoutDurableProviderRequestEvidence() {
    ObjectNode state = completeBudgetState();
    state
        .withObject("usageTotals")
        .put("calls", 1L)
        .put("inputTokens", 7L)
        .put("outputTokens", 11L)
        .put("costUsd", 0.01d)
        .put("latencyMs", 1.0d);
    state
        .withObject("budgetUsage")
        .withObject("committed")
        .put("calls", 1L)
        .put("inputTokens", 7L)
        .put("outputTokens", 11L)
        .put("costUsd", 0.01d);

    DesktopOlympiadEvidenceExporter.UsageAccountingAudit audit =
        DesktopOlympiadEvidenceExporter.usageAccountingAudit(
            temporaryDirectory,
            state,
            new RunExecutionBackend.ExecutionUsage(
                2L, 14L, 22L, new BigDecimal("0.02"), 2.0d));

    assertEquals(1, audit.violations());
    assertEquals("NO_DURABLE_EVIDENCE", audit.durableStatus());
    assertEquals(0, audit.durableEvidenceCount());
  }

  @Test
  void acceptsACompleteCheckpointThatMatchesObservedProviderUsage() {
    ObjectNode state = completeBudgetState();
    state
        .withObject("usageTotals")
        .put("calls", 2L)
        .put("inputTokens", 14L)
        .put("outputTokens", 22L)
        .put("costUsd", 0.02d);
    state
        .withObject("budgetUsage")
        .withObject("committed")
        .put("calls", 2L)
        .put("inputTokens", 14L)
        .put("outputTokens", 22L)
        .put("costUsd", 0.02d);

    assertEquals(
        0,
        DesktopOlympiadEvidenceExporter.budgetViolations(
            state,
            new RunExecutionBackend.ExecutionUsage(
                2L, 14L, 22L, new BigDecimal("0.02"), 2.0d)));
  }

  private static ObjectNode completeBudgetState() {
    ObjectNode state = JsonNodeFactory.instance.objectNode();
    state.set("budgetUsage", JsonNodeFactory.instance.objectNode());
    state.set("pricingSnapshot", JsonNodeFactory.instance.objectNode());
    state
        .withObject("budgetEnvelopes")
        .set("envelopes", JsonNodeFactory.instance.arrayNode());
    return state;
  }
}
