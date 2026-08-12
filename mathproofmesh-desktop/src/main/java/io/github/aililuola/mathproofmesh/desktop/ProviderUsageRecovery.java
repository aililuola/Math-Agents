package io.github.aililuola.mathproofmesh.desktop;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.agent.CallLedger;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.PricingConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Recovers committed provider usage from immutable response artifacts in schema-1 runs. */
final class ProviderUsageRecovery {
  private ProviderUsageRecovery() {}

  static UsageTotals recover(Path runDirectory, SystemConfig config) throws IOException {
    Path artifactRoot =
        runDirectory.resolve("runtime-artifacts").resolve("artifacts").resolve("sha256");
    if (!Files.isDirectory(artifactRoot, NOFOLLOW_LINKS)) {
      return UsageTotals.zero();
    }
    Map<String, PricingConfig> pricing = new LinkedHashMap<>();
    for (AgentConfig agent : config.agents()) {
      pricing.put(agent.id(), agent.pricing());
    }
    Totals totals = new Totals();
    try (Stream<Path> files = Files.walk(artifactRoot, 2)) {
      for (Path file : files.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS)).toList()) {
        if (Files.size(file) > ArtifactStore.DEFAULT_MAX_ARTIFACT_BYTES) {
          continue;
        }
        RecoveredCall call = readCall(file);
        if (call == null) {
          continue;
        }
        PricingConfig agentPricing = pricing.get(call.agentId());
        if (agentPricing == null) {
          throw new IllegalStateException(
              "legacy provider usage references an unknown agent: " + call.agentId());
        }
        totals.add(call, agentPricing);
      }
    }
    return totals.snapshot();
  }

  private static RecoveredCall readCall(Path file) throws IOException {
    String content = Files.readString(file, StandardCharsets.UTF_8);
    JsonNode root;
    try {
      root = ContractObjectMapper.parseTree(content);
    } catch (RuntimeException ignored) {
      return null;
    }
    JsonNode usage = root.path("usage");
    JsonNode input = usage.path("input_tokens");
    JsonNode output = usage.path("output_tokens");
    JsonNode latency = usage.path("latency_ms");
    if (!root.path("agent_id").isTextual()
        || !root.path("provider").isTextual()
        || !root.path("request_id").isTextual()
        || !usage.isObject()
        || !input.isIntegralNumber()
        || !input.canConvertToLong()
        || !output.isIntegralNumber()
        || !output.canConvertToLong()
        || !latency.isNumber()) {
      return null;
    }
    long inputTokens = input.longValue();
    long outputTokens = output.longValue();
    double latencyMs = latency.doubleValue();
    if (inputTokens < 0L
        || outputTokens < 0L
        || !Double.isFinite(latencyMs)
        || latencyMs < 0.0d) {
      throw new IllegalStateException("legacy provider usage contains invalid counters");
    }
    return new RecoveredCall(
        root.path("agent_id").textValue(),
        root.path("provider").textValue(),
        root.path("request_id").textValue(),
        inputTokens,
        outputTokens,
        latencyMs);
  }

  private record RecoveredCall(
      String agentId,
      String provider,
      String requestId,
      long inputTokens,
      long outputTokens,
      double latencyMs) {}

  private static final class Totals {
    private long calls;
    private long inputTokens;
    private long outputTokens;
    private BigDecimal costUsd = BigDecimal.ZERO;
    private double latencyMs;

    private void add(RecoveredCall call, PricingConfig pricing) {
      calls = Math.addExact(calls, 1L);
      inputTokens = Math.addExact(inputTokens, call.inputTokens());
      outputTokens = Math.addExact(outputTokens, call.outputTokens());
      costUsd =
          costUsd.add(
              CallLedger.tokenCost(
                  call.inputTokens(),
                  call.outputTokens(),
                  pricing.inputPerMillion(),
                  pricing.outputPerMillion()));
      latencyMs += call.latencyMs();
      if (!Double.isFinite(latencyMs)) {
        throw new IllegalStateException("legacy provider latency total is not finite");
      }
    }

    private UsageTotals snapshot() {
      return new UsageTotals(calls, inputTokens, outputTokens, costUsd, latencyMs);
    }
  }
}
