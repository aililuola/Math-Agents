package io.github.aililuola.mathproofmesh.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ResumeRequest(
    @JsonProperty("run_id") String runId,
    @JsonProperty("resume_mode") String resumeMode,
    @JsonProperty("additional_budget") Integer additionalBudget,
    @JsonProperty("audited_directive") String auditedDirective) {
  public ResumeRequest {
    runId = RunApiModels.safeRunId(runId);
    resumeMode =
        resumeMode == null || resumeMode.isBlank()
            ? "normal"
            : ActivitySanitizer.identifier(resumeMode, 80);
    if (additionalBudget != null && (additionalBudget < 1 || additionalBudget > 10_000)) {
      throw new IllegalArgumentException("additional_budget must be in [1,10000]");
    }
    if (auditedDirective != null) {
      auditedDirective = ActivitySanitizer.text(auditedDirective, 2_000);
      if (auditedDirective.isBlank()) {
        throw new IllegalArgumentException("audited_directive must not be blank");
      }
    }
  }

  public ResumeRequest(String runId) {
    this(runId, "normal", null, null);
  }
}
