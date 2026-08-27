package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StartRunRequest(
    String problem, String profile, @JsonProperty("run_id") String runId) {
  public StartRunRequest {
    problem = problem == null ? "" : problem.trim();
    if (problem.isEmpty() || problem.length() > 2_000_000) {
      throw new IllegalArgumentException("problem must contain 1 to 2000000 characters");
    }
    profile = DesktopApiModel.safeProfile(profile);
    runId = runId == null || runId.isBlank() ? null : DesktopApiModel.safeRunId(runId);
  }
}
