package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.strategydiversity.GenericStrategyGenerationPolicy;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateSnapshot;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateStatus;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StrategyPortfolioGapFeedback {
  private StrategyPortfolioGapFeedback() {}

  static Map<String, Object> invalidContractErrors(
      String episodeId, StrategySet source, StrategyCandidateSnapshot candidates) {
    Set<String> sourceIds =
        source.strategies().stream()
            .map(StrategyCard::strategyId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<Map<String, String>> errors =
        candidates.records().values().stream()
            .filter(record -> record.episodeId().equals(episodeId))
            .filter(record -> sourceIds.contains(record.strategyId()))
            .filter(record -> record.status() == StrategyCandidateStatus.REJECTED_INVALID)
            .sorted(java.util.Comparator.comparingInt(record -> record.captureOrder()))
            .map(
                record ->
                    Map.of(
                        "strategy_id", record.strategyId(),
                        "deterministic_error", record.detail()))
            .toList();
    List<Map<String, String>> topologyFailures =
        errors.stream()
            .filter(error -> isReachabilityFailure(error.get("deterministic_error")))
            .map(
                error ->
                    Map.of(
                        "strategy_id",
                        error.get("strategy_id"),
                        "code",
                        "MECHANISM_OPERATION_REACHABILITY_MISMATCH",
                        "deterministic_error",
                        error.get("deterministic_error")))
            .toList();
    Map<String, Object> feedback = new LinkedHashMap<>();
    feedback.put(
        "invalid_strategy_ids", errors.stream().map(error -> error.get("strategy_id")).toList());
    feedback.put("invalid_strategy_contract_errors", errors);
    if (!topologyFailures.isEmpty()) {
      feedback.put("mechanism_operation_topology_failures", topologyFailures);
      feedback.put(
          "mechanism_operation_topology_contract",
          new GenericStrategyGenerationPolicy().mechanismOperationTopologyContract());
    }
    return Map.copyOf(feedback);
  }

  private static boolean isReachabilityFailure(String detail) {
    return detail != null
        && (detail.contains("mechanism operation input cannot reach an output")
            || detail.contains("mechanism operation output is not reachable from an input"));
  }
}
