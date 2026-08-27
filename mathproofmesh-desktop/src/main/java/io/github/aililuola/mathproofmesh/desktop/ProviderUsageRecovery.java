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
import io.github.aililuola.mathproofmesh.runstate.ProviderCallUsageEvidence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Recovers committed provider usage from immutable response artifacts in schema-1 runs. */
final class ProviderUsageRecovery {
  private ProviderUsageRecovery() {}

  static List<ProviderCallUsageEvidence> recoverEvidence(
      Path runDirectory, SystemConfig config) throws IOException {
    Map<String, PricingConfig> pricing = new LinkedHashMap<>();
    for (AgentConfig agent : config.agents()) {
      pricing.put(agent.id(), agent.pricing());
    }
    return recoverEvidence(runDirectory, pricing, false);
  }

  static List<ProviderCallUsageEvidence> recoverEmbeddedCostEvidence(Path runDirectory)
      throws IOException {
    return recoverEvidence(runDirectory, Map.of(), true);
  }

  private static List<ProviderCallUsageEvidence> recoverEvidence(
      Path runDirectory, Map<String, PricingConfig> pricing, boolean requireEmbeddedCost)
      throws IOException {
    Path artifactRoot =
        runDirectory.resolve("runtime-artifacts").resolve("artifacts").resolve("sha256");
    if (!Files.isDirectory(artifactRoot, NOFOLLOW_LINKS)) {
      return List.of();
    }
    List<ProviderCallUsageEvidence> recovered = new ArrayList<>();
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
        if (call.embeddedCost() == null && requireEmbeddedCost) {
          continue;
        }
        if (call.embeddedCost() == null && agentPricing == null) {
          throw new IllegalStateException(
              "legacy provider usage references an unknown agent: " + call.agentId());
        }
        recovered.add(call.toEvidence(agentPricing));
      }
    }
    return List.copyOf(recovered);
  }

  @SafeVarargs
  static List<ProviderCallUsageEvidence> mergeEvidence(
      List<ProviderCallUsageEvidence>... evidenceLists) {
    Map<String, ProviderCallUsageEvidence> byRequest = new LinkedHashMap<>();
    for (List<ProviderCallUsageEvidence> evidenceList : evidenceLists) {
      for (ProviderCallUsageEvidence evidence : evidenceList) {
        ProviderCallUsageEvidence existing =
            byRequest.putIfAbsent(evidence.providerRequestId(), evidence);
        if (existing != null && !sameUsage(existing, evidence)) {
          throw new IllegalStateException(
              "conflicting provider usage evidence for request " + evidence.providerRequestId());
        }
        if (existing != null
            && existing.sourceArtifactHash().isBlank()
            && !evidence.sourceArtifactHash().isBlank()) {
          byRequest.put(evidence.providerRequestId(), evidence);
        }
      }
    }
    return List.copyOf(byRequest.values());
  }

  static UsageTotals totals(List<ProviderCallUsageEvidence> evidence) {
    UsageTotals result = UsageTotals.zero();
    for (ProviderCallUsageEvidence call : evidence) {
      result =
          result.plus(
              new UsageTotals(
                  1L,
                  call.inputTokens(),
                  call.outputTokens(),
                  call.estimatedCostUsd(),
                  call.latencyMs()));
    }
    return result;
  }

  private static boolean sameUsage(
      ProviderCallUsageEvidence left, ProviderCallUsageEvidence right) {
    return left.inputTokens() == right.inputTokens()
        && left.outputTokens() == right.outputTokens()
        && left.estimatedCostUsd().compareTo(right.estimatedCostUsd()) == 0
        && Double.compare(left.latencyMs(), right.latencyMs()) == 0;
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
    JsonNode cost = usage.path("cost_usd");
    java.math.BigDecimal embeddedCost = cost.isNumber() ? decimal(cost) : null;
    if (inputTokens < 0L
        || outputTokens < 0L
        || !Double.isFinite(latencyMs)
        || latencyMs < 0.0d
        || (embeddedCost != null && embeddedCost.signum() < 0)) {
      throw new IllegalStateException("legacy provider usage contains invalid counters");
    }
    String requestId = root.path("request_id").textValue().strip();
    String callId = root.path("call_id").asText("").strip();
    Path fileName = file.getFileName();
    if (fileName == null) {
      return null;
    }
    String sourceArtifactHash = fileName.toString();
    return new RecoveredCall(
        root.path("agent_id").textValue(),
        requestId.isEmpty()
            ? "provider-call:" + (callId.isEmpty() ? sourceArtifactHash : callId)
            : requestId,
        inputTokens,
        outputTokens,
        latencyMs,
        embeddedCost,
        sourceArtifactHash);
  }

  private static java.math.BigDecimal decimal(JsonNode node) {
    try {
      return node.decimalValue();
    } catch (ArithmeticException exception) {
      throw new IllegalStateException("legacy provider usage contains invalid cost", exception);
    }
  }

  private record RecoveredCall(
      String agentId,
      String requestId,
      long inputTokens,
      long outputTokens,
      double latencyMs,
      java.math.BigDecimal embeddedCost,
      String sourceArtifactHash) {
    private ProviderCallUsageEvidence toEvidence(PricingConfig pricing) {
      return new ProviderCallUsageEvidence(
          requestId,
          inputTokens,
          outputTokens,
          embeddedCost == null
              ? CallLedger.tokenCost(
                  inputTokens,
                  outputTokens,
                  pricing.inputPerMillion(),
                  pricing.outputPerMillion())
              : embeddedCost,
          latencyMs,
          sourceArtifactHash);
    }
  }
}
