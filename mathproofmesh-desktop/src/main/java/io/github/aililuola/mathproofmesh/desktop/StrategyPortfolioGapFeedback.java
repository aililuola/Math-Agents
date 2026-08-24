package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateSnapshot;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateStatus;
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
    return Map.of(
        "invalid_strategy_ids",
        errors.stream().map(error -> error.get("strategy_id")).toList(),
        "invalid_strategy_contract_errors",
        errors);
  }
}
