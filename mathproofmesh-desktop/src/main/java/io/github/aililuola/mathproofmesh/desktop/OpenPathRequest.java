package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;

public record OpenPathRequest(String kind, @JsonProperty("run_id") String runId) {
  public OpenPathRequest {
    kind = kind == null ? "" : kind.trim();
    if (!Set.of("data", "runs", "logs", "run").contains(kind)) {
      throw new IllegalArgumentException("unsupported desktop path kind");
    }
    runId =
        runId == null || runId.isBlank() ? null : DesktopApiModel.safeRunId(runId);
    if ("run".equals(kind) && runId == null) {
      throw new IllegalArgumentException("run_id is required");
    }
  }
}
