package io.github.aililuola.mathproofmesh.orchestration;

import java.util.List;

/** Fixed pre-Temporal stage machine and first-round route isolation. */
public final class RoutePipelineFunctions {
  private RoutePipelineFunctions() {}

  public static final List<RunStage> FIXED_STAGES = List.of(RunStage.values());

  public static RunStage next(RunStage current) {
    int index = FIXED_STAGES.indexOf(current);
    if (index < 0 || index + 1 >= FIXED_STAGES.size()) {
      throw new IllegalStateException("run stage has no successor: " + current);
    }
    return FIXED_STAGES.get(index + 1);
  }

  public static RouteContext isolatedInitialContext(
      String strategyId,
      List<String> relevantVerifiedFacts,
      List<String> rawTranscripts,
      List<String> otherRouteReasoning) {
    return new RouteContext(
        strategyId,
        relevantVerifiedFacts == null ? List.of() : relevantVerifiedFacts,
        List.of(),
        List.of(),
        true);
  }

  public enum RunStage {
    FREEZE_PROBLEM,
    TRIAGE,
    STRATEGY_DIVERSITY,
    ROUTE_ADMISSION_AND_TEAM,
    ISOLATED_EXPLORATION,
    WORKING_DELTA,
    INDEPENDENT_REVIEW,
    COMMITTED_CHECKPOINT,
    CLAIM_MEMORY_GRAPH,
    CROSS_ROUTE_BROKER,
    INSPIRATION,
    META_REVIEW,
    SCHEDULER_DECISION,
    SYNTHESIS,
    BLIND_FINAL_REVIEW
  }

  public enum SchedulerDecision {
    WIDEN,
    DEEPEN,
    VERIFY,
    REVISE,
    SYNTHESIZE,
    STOP
  }

  public record RouteContext(
      String strategyId,
      List<String> verifiedFacts,
      List<String> rawTranscripts,
      List<String> otherRouteReasoning,
      boolean isolated) {
    public RouteContext {
      strategyId = strategyId == null ? "" : strategyId.strip();
      verifiedFacts = List.copyOf(verifiedFacts);
      rawTranscripts = List.copyOf(rawTranscripts);
      otherRouteReasoning = List.copyOf(otherRouteReasoning);
    }

    public List<String> verifiedFacts() {
      return List.copyOf(verifiedFacts);
    }

    public List<String> rawTranscripts() {
      return List.copyOf(rawTranscripts);
    }

    public List<String> otherRouteReasoning() {
      return List.copyOf(otherRouteReasoning);
    }
  }
}
