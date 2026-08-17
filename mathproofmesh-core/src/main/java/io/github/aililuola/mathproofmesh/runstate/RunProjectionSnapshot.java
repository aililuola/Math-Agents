package io.github.aililuola.mathproofmesh.runstate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RunProjectionSnapshot(
    String authorityHash,
    RunReportStatus reportStatus,
    String runResultRef,
    String runResultHash,
    String desktopMetadataRef,
    String desktopMetadataHash,
    String reportRef,
    String reportHash,
    long latestActivitySequence,
    List<String> projectionErrors,
    String projectionHash) {

  public RunProjectionSnapshot {
    authorityHash = RunStateHashes.optional(authorityHash);
    reportStatus = reportStatus == null ? RunReportStatus.ABSENT : reportStatus;
    runResultRef = RunStateHashes.optional(runResultRef);
    runResultHash = RunStateHashes.optional(runResultHash);
    desktopMetadataRef = RunStateHashes.optional(desktopMetadataRef);
    desktopMetadataHash = RunStateHashes.optional(desktopMetadataHash);
    reportRef = RunStateHashes.optional(reportRef);
    reportHash = RunStateHashes.optional(reportHash);
    projectionErrors = projectionErrors == null ? List.of() : List.copyOf(projectionErrors);
    if (latestActivitySequence < 0L) {
      throw new IllegalArgumentException("latestActivitySequence must not be negative");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("authorityHash", authorityHash);
    payload.put("reportStatus", reportStatus);
    payload.put("runResultRef", runResultRef);
    payload.put("runResultHash", runResultHash);
    payload.put("desktopMetadataRef", desktopMetadataRef);
    payload.put("desktopMetadataHash", desktopMetadataHash);
    payload.put("reportRef", reportRef);
    payload.put("reportHash", reportHash);
    payload.put("latestActivitySequence", latestActivitySequence);
    payload.put("projectionErrors", projectionErrors);
    projectionHash = RunStateHashes.generatedOrVerified(projectionHash, payload, "projection");
  }

  public static RunProjectionSnapshot absent(String authorityHash) {
    return new RunProjectionSnapshot(
        Objects.requireNonNullElse(authorityHash, ""),
        RunReportStatus.ABSENT,
        "",
        "",
        "",
        "",
        "",
        "",
        0L,
        List.of(),
        null);
  }

  @Override
  public List<String> projectionErrors() {
    return List.copyOf(projectionErrors);
  }
}
