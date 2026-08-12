package io.github.aililuola.mathproofmesh.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aililuola.mathproofmesh.api.RunApiModels.ApiEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RunApiEventVocabularyTest {
  private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

  @ParameterizedTest
  @ValueSource(strings = {"sandbox_preflight", "computation", "agent_failed", "run_failed"})
  void acceptsEveryDesktopLiveProgressEvent(String type) {
    ApiEvent event =
        new ApiEvent(
            1L,
            type,
            "goal_preflight",
            null,
            0L,
            "completed",
            "Bounded desktop progress",
            null,
            TRACE_ID);

    assertEquals(type, event.type());
  }

  @ParameterizedTest
  @CsvSource({
    "stage_started, stage_changed",
    "stage_completed, stage_changed",
    "problem_frozen, stage_changed",
    "route_admitted, route_updated",
    "checkpoint_committed, checkpoint",
    "broker_receipt_recorded, message",
    "validation_escalation_planned, verification",
    "inspiration_candidate_filtered, route_updated",
    "run_cancelled, error"
  })
  void mapsDetailedBackendProgressToTheStablePublicVocabulary(
      String detailedType, String publicType) {
    assertEquals(publicType, RunApiModels.publicEventType(detailedType));
  }
}
