package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class DesktopProviderUsageHardCrashTestSupport {
  private DesktopProviderUsageHardCrashTestSupport() {}

  static void writeProviderArtifacts(Path runDirectory, String runId, int count) {
    String agentId = DesktopLiveFailureUsageTestSupport.config().agents().getFirst().id();
    ArtifactStore store = new ArtifactStore(runDirectory.resolve("runtime-artifacts"), runId);
    for (int index = 1; index <= count; index++) {
      store.writeText(
          ContractObjectMapper.write(
              Map.of(
                  "agent_id", agentId,
                  "call_id", "fixture-call-" + index,
                  "provider", "mock",
                  "model", "fixture",
                  "request_id", "fixture-request-" + index,
                  "stage", "fixture",
                  "text", "{}",
                  "usage",
                      Map.of(
                          "input_tokens", 7,
                          "output_tokens", 11,
                          "latency_ms", 0.5d,
                          "cost_usd", 0.0d),
                  "metadata", Map.of())),
          "application/json",
          "provider-response:fixture:" + agentId,
          "short-term",
          "provider_response");
    }
  }

  static void writeCheckpointUsage(Path state, long calls) throws Exception {
    ObjectNode checkpoint =
        (ObjectNode) ContractObjectMapper.parseTree(Files.readString(state));
    ObjectNode usage = checkpoint.putObject("usageTotals");
    usage.put("calls", calls);
    usage.put("inputTokens", Math.multiplyExact(calls, 7L));
    usage.put("outputTokens", Math.multiplyExact(calls, 11L));
    usage.put("costUsd", 0.0d);
    usage.put("latencyMs", calls * 0.5d);
    Files.writeString(state, ContractObjectMapper.write(checkpoint));
  }

  static void deleteCheckpoint(Path state) {
    try {
      Files.deleteIfExists(state);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("could not delete the simulated pre-checkpoint state", exception);
    }
  }

  static final class SimulatedProcessTermination extends Error {
    private static final long serialVersionUID = 1L;

    SimulatedProcessTermination() {
      super("simulated process termination after durable provider response artifact");
    }
  }
}
