package io.github.aililuola.mathproofmesh.runstate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes the compatibility result as an atomic, hash-bound projection. */
public final class RunResultProjectionService {
  public RunProjectionReceipt project(
      Path runDirectory, RunStateSnapshot state, Map<String, Object> details) {
    Objects.requireNonNull(runDirectory, "runDirectory");
    Objects.requireNonNull(state, "state");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("authority_state_hash", state.authority().authorityHash());
    payload.put("state_sequence", state.authority().authoritySequence());
    payload.put("execution_status", state.authority().executionStatus());
    payload.put("math_status", state.authority().mathStatus());
    payload.put("usage_status", state.authority().usageStatus());
    payload.put("campaign_status", state.authority().campaignStatus());
    payload.put("terminal_reason", state.authority().terminalReason());
    payload.put("recoverable", state.authority().recoverable());
    payload.put("provider_calls", state.authority().usage().providerCalls());
    payload.put("total_usage", state.authority().usage());
    if (details != null) {
      payload.putAll(details);
    }
    Path target = runDirectory.resolve("structured/run_result.json");
    String hash = AtomicRunProjectionWriter.write(target, payload);
    return new RunProjectionReceipt(
        state.authority().authorityHash(),
        "structured/run_result.json",
        hash,
        state.projection().reportStatus(),
        List.of(),
        Instant.now());
  }
}
