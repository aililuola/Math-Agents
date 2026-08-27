package io.github.aililuola.mathproofmesh.api;

import java.util.List;
import java.util.Map;

/** Deterministic, provider-free demo fixtures shared by CLI and HTTP tests. */
public final class MockDemoFunctions {
  public static final String PROBLEM =
      "Prove that the sum of the first n odd positive integers is n squared.";

  private MockDemoFunctions() {}

  public static SolveRequest request(String runId) {
    return new SolveRequest(PROBLEM, runId, PROBLEM);
  }

  public static Map<String, Object> probe(boolean completion) {
    return Map.of(
        "provider",
        "mock",
        "model",
        "mock",
        "reachable",
        true,
        "completion_checked",
        completion,
        "live_provider_calls",
        0,
        "roles",
        List.of("planner", "explorer", "verifier", "synthesizer"));
  }
}
