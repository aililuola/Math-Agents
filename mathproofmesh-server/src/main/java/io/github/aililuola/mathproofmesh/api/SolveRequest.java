package io.github.aililuola.mathproofmesh.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SolveRequest(
    String problem,
    @JsonProperty("run_id") String runId,
    @JsonProperty("canonical_statement") String canonicalStatement,
    String profile) {
  public SolveRequest {
    problem = ActivitySanitizer.text(problem, 100_000);
    if (problem.isBlank()) {
      throw new IllegalArgumentException("problem must not be blank");
    }
    runId = RunApiModels.safeOptionalRunId(runId);
    if (canonicalStatement != null) {
      canonicalStatement = ActivitySanitizer.text(canonicalStatement, 100_000);
      if (canonicalStatement.isBlank()) {
        throw new IllegalArgumentException("canonical_statement must not be blank");
      }
    }
    if (profile != null) {
      profile = ActivitySanitizer.identifier(profile, 80);
    }
  }

  public SolveRequest(String problem, String runId, String canonicalStatement) {
    this(problem, runId, canonicalStatement, null);
  }
}
