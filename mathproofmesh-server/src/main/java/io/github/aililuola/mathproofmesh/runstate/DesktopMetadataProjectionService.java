package io.github.aililuola.mathproofmesh.runstate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes desktop lifecycle metadata from, never into, the authority snapshot. */
public final class DesktopMetadataProjectionService {
  public RunProjectionReceipt project(
      Path runDirectory, RunStateSnapshot state, Map<String, Object> desktopFields) {
    Objects.requireNonNull(runDirectory, "runDirectory");
    Objects.requireNonNull(state, "state");
    Map<String, Object> payload = new LinkedHashMap<>();
    if (desktopFields != null) {
      payload.putAll(desktopFields);
    }
    payload.put("authority_state_hash", state.authority().authorityHash());
    payload.put("state_sequence", state.authority().authoritySequence());
    payload.put("execution_status", state.authority().executionStatus());
    payload.put("math_status", state.authority().mathStatus());
    payload.put("usage_status", state.authority().usageStatus());
    payload.put("campaign_status", state.authority().campaignStatus());
    payload.put("report_status", state.projection().reportStatus());
    Path target = runDirectory.resolve("desktop_run.json");
    String hash = AtomicRunProjectionWriter.write(target, payload);
    return new RunProjectionReceipt(
        state.authority().authorityHash(),
        "desktop_run.json",
        hash,
        state.projection().reportStatus(),
        List.of(),
        Instant.now());
  }
}
