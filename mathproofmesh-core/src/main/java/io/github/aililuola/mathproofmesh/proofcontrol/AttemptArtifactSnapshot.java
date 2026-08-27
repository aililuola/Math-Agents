package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.LinkedHashMap;
import java.util.Map;

public record AttemptArtifactSnapshot(
    Map<String, AttemptArtifactRecord> records,
    Map<String, String> attemptReviewReportIds) {

  public AttemptArtifactSnapshot {
    records = records == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(records));
    attemptReviewReportIds =
        attemptReviewReportIds == null
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(attemptReviewReportIds));
  }

  public static AttemptArtifactSnapshot empty() {
    return new AttemptArtifactSnapshot(Map.of(), Map.of());
  }
}
