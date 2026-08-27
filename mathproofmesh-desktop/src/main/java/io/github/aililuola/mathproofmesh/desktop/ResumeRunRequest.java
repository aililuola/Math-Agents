package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;

public record ResumeRunRequest(
    String profile, @JsonProperty("resume_mode") String resumeMode) {
  private static final Set<String> MODES =
      Set.of("normal", "reopen_with_pivot", "reset_stagnation", "replay_stage");

  public ResumeRunRequest {
    profile = DesktopApiModel.safeProfile(profile);
    resumeMode = resumeMode == null || resumeMode.isBlank() ? "normal" : resumeMode.trim();
    if (!MODES.contains(resumeMode)) {
      throw new IllegalArgumentException(
          "resume_mode must be one of: normal, reopen_with_pivot, reset_stagnation, replay_stage");
    }
  }
}
