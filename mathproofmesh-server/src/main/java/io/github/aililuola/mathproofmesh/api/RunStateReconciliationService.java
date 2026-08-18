package io.github.aililuola.mathproofmesh.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Recovers durable checkpoint projections before an execution failure is returned. */
public final class RunStateReconciliationService {
  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  public RunExecutionBackend.RunExecutionResult reconcileFailure(
      Path runDirectory, RunExecutionBackend.RunExecutionResult failure) {
    Path checkpoint = runDirectory.resolve("structured/desktop-solve-state.json");
    if (!Files.isRegularFile(checkpoint)) {
      return failure;
    }
    try {
      JsonNode root = JSON.readTree(Files.readAllBytes(checkpoint));
      List<RouteView> routes = routes(root.path("routes"));
      List<String> verifiedClaims = verifiedClaims(root.path("claimLifecycle").path("entries"));
      JsonNode usage = root.path("usageTotals");
      RunExecutionBackend.ExecutionUsage recoveredUsage =
          new RunExecutionBackend.ExecutionUsage(
              usage.path("calls").asLong(0),
              usage.path("inputTokens").asLong(0),
              usage.path("outputTokens").asLong(0),
              decimal(usage.path("costUsd")),
              usage.path("latencyMs").asDouble(0));
      RunExecutionBackend.ExecutionUsage reconciledUsage =
          dominates(recoveredUsage, failure.usage()) ? recoveredUsage : failure.usage();
      int logicalSteps = Math.max(failure.logicalSteps(), root.path("roundIndex").asInt(0));
      return new RunExecutionBackend.RunExecutionResult(
          failure.status(),
          failure.currentStage(),
          failure.summary(),
          routes.isEmpty() ? failure.routes() : routes,
          verifiedClaims.isEmpty() ? failure.verifiedLocalClaimIds() : verifiedClaims,
          failure.reportBody(),
          logicalSteps,
          reconciledUsage,
          failure.runState());
    } catch (IOException | RuntimeException exception) {
      return failure;
    }
  }

  private static boolean dominates(
      RunExecutionBackend.ExecutionUsage candidate,
      RunExecutionBackend.ExecutionUsage other) {
    return candidate.providerCalls() >= other.providerCalls()
        && candidate.inputTokens() >= other.inputTokens()
        && candidate.outputTokens() >= other.outputTokens()
        && candidate.estimatedCostUsd().compareTo(other.estimatedCostUsd()) >= 0
        && candidate.latencyMs() >= other.latencyMs();
  }

  private static List<RouteView> routes(JsonNode nodes) {
    if (!nodes.isArray()) {
      return List.of();
    }
    List<RouteView> result = new ArrayList<>();
    for (JsonNode node : nodes) {
      String id = node.path("routeId").asText("");
      if (!id.isBlank()) {
        result.add(
            new RouteView(
                id,
                node.path("terminal").asBoolean(false) ? "completed" : "interrupted",
                "Recovered from durable semantic checkpoint",
                List.of()));
      }
    }
    return List.copyOf(result);
  }

  private static List<String> verifiedClaims(JsonNode records) {
    Set<String> ids = new LinkedHashSet<>();
    if (records.isArray()) {
      records.forEach(node -> addVerified(ids, node));
    } else if (records.isObject()) {
      records.properties().forEach(entry -> addVerified(ids, entry.getValue(), entry.getKey()));
    }
    return List.copyOf(ids);
  }

  private static void addVerified(Set<String> ids, JsonNode node) {
    addVerified(ids, node, node.path("claimId").asText(""));
  }

  private static void addVerified(Set<String> ids, JsonNode node, String fallbackId) {
    String lifecycleState =
        node.path("state").asText(node.path("status").asText(""));
    if (java.util.Set.of(
            "VERIFIED",
            "verified",
            "LOCALLY_VERIFIED",
            "INDEPENDENTLY_VERIFIED",
            "REFEREE_ACCEPTED",
            "FACT_CANDIDATE",
            "EXTERNALLY_ADMITTED_FACT")
        .contains(lifecycleState)) {
      String id = node.path("claimId").asText(fallbackId);
      if (!id.isBlank()) {
        ids.add(id);
      }
    }
  }

  private static BigDecimal decimal(JsonNode node) {
    try {
      return node.isNumber() || node.isTextual() ? node.decimalValue() : BigDecimal.ZERO;
    } catch (ArithmeticException exception) {
      return BigDecimal.ZERO;
    }
  }
}
