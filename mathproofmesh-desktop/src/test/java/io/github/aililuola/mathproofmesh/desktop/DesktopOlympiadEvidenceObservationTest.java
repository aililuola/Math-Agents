package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

final class DesktopOlympiadEvidenceObservationTest {
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
