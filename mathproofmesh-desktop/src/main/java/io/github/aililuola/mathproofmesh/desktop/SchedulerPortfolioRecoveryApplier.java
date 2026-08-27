package io.github.aililuola.mathproofmesh.desktop;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyArchive;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioApplyReceipt;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Atomically appends a one-shot scheduler recovery portfolio without replacing active routes. */
final class SchedulerPortfolioRecoveryApplier {
  private SchedulerPortfolioRecoveryApplier() {}

  static Result apply(
      String episodeId,
      DesktopSolveCoordinator.StrategyPortfolioPreparation preparation,
      List<String> routeIds,
      StrategyArchive archive,
      Map<String, StrategyBlueprintCompiler.Compilation> blueprints,
      Map<String, ProofControlModels.GoalLink> goalLinks,
      AtomicInteger nextStrategyIndex,
      int round,
      String stageBefore,
      Persistence persistence,
      MutableState state) {
    Map<String, CandidateProjection> candidates =
        preparation.prepared().entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry ->
                        new CandidateProjection(
                            entry.getValue().strategy(),
                            entry.getValue().controlStrategy(),
                            entry.getValue().blueprint(),
                            entry.getValue().goalLink())));
    return apply(
        new Request(
            episodeId,
            preparation.decision().decisionHash(),
            preparation.decision().selectedStrategyIds(),
            candidates,
            routeIds,
            archive,
            blueprints,
            goalLinks,
            nextStrategyIndex,
            round,
            stageBefore,
            persistence),
        state);
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "The atomic recovery boundary must rethrow the original failure after restoring every "
              + "mutated projection so the scheduler preserves the provider failure classification.")
  static Result apply(Request request, MutableState state) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(state, "state");
    StrategyArchive.Snapshot archiveBefore = request.archive().snapshot();
    Map<String, StrategyBlueprintCompiler.Compilation> blueprintsBefore =
        Map.copyOf(request.blueprints());
    Map<String, ProofControlModels.GoalLink> goalLinksBefore =
        Map.copyOf(request.goalLinks());
    List<StrategyCard> admittedBefore = state.admittedStrategies();
    var portfoliosBefore = state.portfolios().snapshot();
    int nextBefore = request.nextStrategyIndex().get();
    boolean persistAttempted = false;
    try {
      Set<String> existingIds =
          admittedBefore.stream().map(StrategyCard::strategyId).collect(Collectors.toUnmodifiableSet());
      List<CandidateProjection> additions =
          request.selectedStrategyIds().stream()
              .filter(strategyId -> !existingIds.contains(strategyId))
              .map(request.candidates()::get)
              .filter(Objects::nonNull)
              .toList();
      for (CandidateProjection candidate : additions) {
        StrategyCard strategy = candidate.strategy();
        request
            .archive()
            .archive(candidate.controlStrategy(), "strategy://" + strategy.strategyId(), request.round());
        request.blueprints().put(strategy.strategyId(), candidate.blueprint());
        request.goalLinks().put(strategy.strategyId(), candidate.goalLink());
      }
      List<StrategyCard> expanded = new ArrayList<>(admittedBefore);
      additions.stream().map(CandidateProjection::strategy).forEach(expanded::add);
      state.setAdmittedStrategies(expanded);
      String activeHash =
          CanonicalJson.stableHash(
              Map.of(
                  "admitted",
                  state.admittedStrategies().stream().map(StrategyCard::strategyId).toList(),
                  "routes",
                  request.routeIds(),
                  "archive",
                  request.archive().snapshot()));
      StrategyPortfolioApplyReceipt receipt =
          new StrategyPortfolioApplyReceipt(
              "strategy-portfolio-receipt-" + activeHash.substring(0, 20),
              "strategy-portfolio-plan-" + request.decisionHash().substring(0, 20),
              additions.stream().map(value -> value.strategy().strategyId()).toList(),
              List.of(),
              activeHash);
      state.portfolios().recordReceipt(request.episodeId(), receipt);
      persistAttempted = true;
      request.persistence().persist("exhausted_portfolio_recovery_staged");
      return new Result(additions.stream().map(CandidateProjection::strategy).toList(), receipt);
    } catch (RuntimeException exception) {
      request.archive().restore(archiveBefore);
      request.blueprints().clear();
      request.blueprints().putAll(blueprintsBefore);
      request.goalLinks().clear();
      request.goalLinks().putAll(goalLinksBefore);
      state.setAdmittedStrategies(admittedBefore);
      state.setPortfolios(StrategyPortfolioRegistry.restore(portfoliosBefore));
      request.nextStrategyIndex().set(nextBefore);
      try {
        request.persistence().rollback(request.stageBefore(), persistAttempted);
      } catch (RuntimeException rollbackFailure) {
        exception.addSuppressed(rollbackFailure);
      }
      throw exception;
    }
  }

  record CandidateProjection(
      StrategyCard strategy,
      ProofControlModels.Strategy controlStrategy,
      StrategyBlueprintCompiler.Compilation blueprint,
      ProofControlModels.GoalLink goalLink) {
    CandidateProjection {
      Objects.requireNonNull(strategy, "strategy");
      Objects.requireNonNull(controlStrategy, "controlStrategy");
      Objects.requireNonNull(blueprint, "blueprint");
      Objects.requireNonNull(goalLink, "goalLink");
    }
  }

  record Request(
      String episodeId,
      String decisionHash,
      List<String> selectedStrategyIds,
      Map<String, CandidateProjection> candidates,
      List<String> routeIds,
      StrategyArchive archive,
      Map<String, StrategyBlueprintCompiler.Compilation> blueprints,
      Map<String, ProofControlModels.GoalLink> goalLinks,
      AtomicInteger nextStrategyIndex,
      int round,
      String stageBefore,
      Persistence persistence) {
    Request {
      episodeId = require(episodeId, "episodeId");
      decisionHash = require(decisionHash, "decisionHash");
      selectedStrategyIds = List.copyOf(selectedStrategyIds);
      candidates = Map.copyOf(candidates);
      routeIds = List.copyOf(routeIds);
      Objects.requireNonNull(archive, "archive");
      Objects.requireNonNull(blueprints, "blueprints");
      Objects.requireNonNull(goalLinks, "goalLinks");
      Objects.requireNonNull(nextStrategyIndex, "nextStrategyIndex");
      if (round < 0) {
        throw new IllegalArgumentException("round must not be negative");
      }
      stageBefore = require(stageBefore, "stageBefore");
      Objects.requireNonNull(persistence, "persistence");
    }
  }

  record Result(List<StrategyCard> additions, StrategyPortfolioApplyReceipt receipt) {
    Result {
      additions = List.copyOf(additions);
      Objects.requireNonNull(receipt, "receipt");
    }

    boolean changed() {
      return !additions.isEmpty();
    }
  }

  static final class MutableState {
    private List<StrategyCard> admittedStrategies;
    private StrategyPortfolioRegistry portfolios;

    MutableState(List<StrategyCard> admittedStrategies, StrategyPortfolioRegistry portfolios) {
      setAdmittedStrategies(admittedStrategies);
      setPortfolios(portfolios);
    }

    List<StrategyCard> admittedStrategies() {
      return admittedStrategies;
    }

    void setAdmittedStrategies(List<StrategyCard> value) {
      admittedStrategies = List.copyOf(value);
    }

    StrategyPortfolioRegistry portfolios() {
      return portfolios;
    }

    void setPortfolios(StrategyPortfolioRegistry value) {
      portfolios = Objects.requireNonNull(value, "portfolios");
    }
  }

  interface Persistence {
    void persist(String reason);

    void rollback(String stageBefore, boolean persistAttempted);
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
