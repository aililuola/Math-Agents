package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DeepExplorationRuntimeParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_stage_thinking_policy_is_artifact_aware_and_tiered",
        "test_exact_provider_length_failure_carries_64k_recovery_budget",
        "test_continuous_reasoning_is_not_stopped_by_elapsed_time",
        "test_five_minutes_without_sse_data_is_transport_stall",
        "test_missing_first_sse_chunk_uses_shorter_transport_timeout",
        "test_distinct_first_chunk_stalls_open_shared_provider_circuit",
        "test_queued_call_cannot_read_another_calls_stream_progress",
        "test_queued_call_is_labeled_as_queued_not_live_streaming",
        "test_optional_batch_cancels_siblings_when_provider_circuit_opens",
        "test_started_artifact_is_not_subject_to_no_content_cutoff",
        "test_no_artifact_failure_does_not_repeat_the_full_explorer_call",
        "test_deep_call_does_not_start_without_capacity_for_recovery");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("DeepExplorationRuntimeParityTest", authorityFunction);
  }
}
