package io.github.aililuola.mathproofmesh.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ConfigInvariants {
  private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Pattern PINNED_IMAGE =
      Pattern.compile("[^\\s@]+@sha256:[0-9a-fA-F]{64}");

  private ConfigInvariants() {}

  static void validate(AgentConfig config) {
    if (!"mock".equals(config.provider())
        && isBlank(config.apiKeyEnv())
        && config.apiKey() == null) {
      throw invalid("agent " + config.id() + ": api_key_env or api_key is required");
    }
    if (config.reasoningEffort() != null && !config.thinkingEnabled()) {
      throw invalid(
          "agent " + config.id() + ": reasoning_effort requires thinking_enabled=true");
    }
    if (config.maxOutputTokens() > config.providerMaxOutputTokens()) {
      throw invalid(
          "agent " + config.id()
              + ": max_output_tokens cannot exceed provider_max_output_tokens");
    }
    if ("deepseek".equals(config.provider())
        && !config.model().startsWith("deepseek-")) {
      throw invalid(
          "agent " + config.id()
              + ": DeepSeek provider requires a DeepSeek model identifier");
    }
    if (config.userId() != null && !USER_ID.matcher(config.userId()).matches()) {
      throw invalid("user_id may contain only letters, digits, '-' and '_'");
    }
  }

  static void validate(BudgetConfig config) {
    double shares =
        config.breadthShare()
            + config.depthShare()
            + config.verificationShare()
            + config.synthesisShare();
    if (Math.abs(shares - 1.0d) > 1.0e-6d) {
      throw invalid("breadth/depth/verification/synthesis shares must sum to 1.0");
    }
    if (config.initialPaths() > config.maxPaths()) {
      throw invalid("initial_paths cannot exceed max_paths");
    }
    if (config.strategiesToGenerate() < config.initialPaths()) {
      throw invalid("strategies_to_generate must be >= initial_paths");
    }
  }

  static void validate(SchedulerConfig config) {
    if (config.globalNoProgressRoundsBeforeStop()
        <= config.globalNoProgressRoundsBeforeMetaPivot()) {
      throw invalid("global no-progress stop must occur after the meta-pivot round");
    }
  }

  static void validate(BrokerConfig config) {
    if (config.ambiguousMatchLow() > config.ambiguousMatchHigh()) {
      throw invalid("ambiguous_match_low cannot exceed ambiguous_match_high");
    }
  }

  static void validate(InspirationConfig config) {
    if (config.surpriseBudgetMinCalls() > config.surpriseBudgetMaxCalls()) {
      throw invalid("surprise_budget_min_calls cannot exceed surprise_budget_max_calls");
    }
    if (config.maxReviewedProposalsPerTask() > config.activeProposalsPerTask()) {
      throw invalid(
          "max_reviewed_proposals_per_task cannot exceed active_proposals_per_task");
    }
    if (config.coldContextProposalsPerTask() > config.activeProposalsPerTask()) {
      throw invalid(
          "cold_context_proposals_per_task cannot exceed active_proposals_per_task");
    }
    List<String> roles = config.proposerGeneralistRoles();
    if (new HashSet<>(roles).size() != roles.size()) {
      throw invalid("proposer_generalist_roles cannot contain duplicates");
    }
    if (config.composerMaxSources() > config.maxReviewedProposalsPerTask()) {
      throw invalid(
          "composer_max_sources cannot exceed max_reviewed_proposals_per_task");
    }
    if (config.proposalsEnterFactMemory()) {
      throw invalid("inspiration proposals must enter InsightMemory first");
    }
    double noveltyTotal =
        config.noveltyRepresentationWeight()
            + config.noveltyMechanismWeight()
            + config.noveltyObjectWeight()
            + config.noveltyTransformationWeight()
            + config.noveltyPrincipleWeight()
            + config.noveltyObligationWeight();
    if (noveltyTotal <= 0.0d) {
      throw invalid("at least one novelty weight must be positive");
    }
  }

  static void validate(AgentCapabilityConfig config) {
    if (config.useSelfReportedConfidence()) {
      throw invalid("self-reported confidence cannot update capability");
    }
  }

  static void validate(TopologyConfig config) {
    if ("hierarchical_sparse".equals(config.mode())) {
      if (!"off".equals(config.proofGraph().mode())
          && !config.proofGraph().enabled()) {
        throw invalid("proof_graph.mode requires proof_graph.enabled=true");
      }
      if (config.routeTeams().enabled()
          && !config.typedCommunication().enabled()) {
        throw invalid("route teams require typed communication");
      }
      if (config.crossRoute().enabled()
          && !config.typedCommunication().enabled()) {
        throw invalid("cross-route routing requires typed communication");
      }
      if (!"off".equals(config.inspiration().mode())
          && !config.inspiration().enabled()) {
        throw invalid("inspiration.mode requires inspiration.enabled=true");
      }
    }

    ProofControlConfig control = config.proofControl();
    if (!"off".equals(control.mode()) && !control.enabled()) {
      throw invalid("proof_control.mode requires proof_control.enabled=true");
    }
    if ("active".equals(control.mode())) {
      if (!"hierarchical_sparse".equals(config.mode())) {
        throw invalid("active proof control requires hierarchical_sparse");
      }
      if (!config.proofGraph().enabled()
          || !"active".equals(config.proofGraph().mode())) {
        throw invalid("active proof control requires active proof graph");
      }
      if (!config.typedMemory().enabled()) {
        throw invalid("active proof control requires typed memory");
      }
      if (!config.typedCommunication().enabled()) {
        throw invalid("active proof control requires typed communication");
      }
    }
    FalsificationFastLaneControlConfig fastLane =
        control.falsificationFastLane();
    if (fastLane.autoFactPromotion()) {
      throw invalid("fast-lane computation must never auto-promote Fact");
    }
    if (fastLane.allowSandboxedPython()) {
      throw invalid("automatic falsification fast lane cannot use sandboxed Python");
    }
  }

  static void validate(VerificationConfig config) {
    if (config.leanSandboxImage() != null
        && !config.leanSandboxImage().contains("@sha256:")) {
      throw invalid("lean_sandbox_image must be pinned by sha256 digest");
    }
  }

  static void validate(ExplorationTierPolicyConfig config) {
    if (config.artifactRecoveryTokens() >= config.outputTokens()) {
      throw invalid("artifact_recovery_tokens must be lower than output_tokens");
    }
  }

  static void validate(DeepExplorationPolicyConfig config) {
    List<Integer> values =
        config.tiers().stream()
            .map(ExplorationTierPolicyConfig::outputTokens)
            .toList();
    ConfigValidation.strictlyIncreasing("deep exploration tiers", values);
    if (config.partialRepairMaxOutputTokens() > values.getLast()) {
      throw invalid("partial_repair_max_output_tokens cannot exceed the highest tier");
    }
    if (!config.allowParallelDistinctSignatures()) {
      throw invalid("distinct deep-exploration signatures must remain parallelizable");
    }
  }

  static void validate(ComputationConfig config) {
    if (config.softExperimentsPerPath() > config.hardExperimentsPerPath()) {
      throw invalid("soft_experiments_per_path cannot exceed hard_experiments_per_path");
    }
    if (config.executePlannerHintsImmediately()) {
      throw invalid(
          "planner computation hints are non-executable under reasoning_first policy");
    }
    if (config.sandboxedPythonEnabled()
        && (config.sandboxImage() == null
            || !PINNED_IMAGE.matcher(config.sandboxImage()).matches())) {
      throw invalid(
          "sandboxed Python requires sandbox_image pinned with @sha256:<64 hex>");
    }
  }

  static void validate(RuntimeConfig config) {
    for (Map.Entry<String, Integer> entry : config.stageOutputTokenLimits().entrySet()) {
      if (entry.getKey().isBlank()) {
        throw invalid("stage_output_token_limits keys must be non-empty");
      }
      if (entry.getValue() < 256 || entry.getValue() > 384000) {
        throw invalid(
            "stage_output_token_limits values must be between 256 and 384000");
      }
    }
    if (config.stageThinkingModes().keySet().stream().anyMatch(String::isBlank)) {
      throw invalid("stage_thinking_modes keys must be non-empty");
    }
    validateHttpStatuses(config.providerTerminalHttpStatuses());
    validateHttpStatuses(config.providerSharedAuthHttpStatuses());
    List<Integer> tiers = config.explorationOutputTokenTiers();
    if (tiers.stream().anyMatch(value -> value < 512 || value > 384000)) {
      throw invalid("exploration token tiers must be between 512 and 384000");
    }
    ConfigValidation.strictlyIncreasing("exploration token tiers", tiers);
  }

  static void validate(SystemConfig config) {
    List<AgentConfig> agents = config.agents();
    if (agents.stream().noneMatch(AgentConfig::enabled)) {
      throw invalid("at least one enabled agent is required");
    }
    Set<String> ids = new HashSet<>();
    if (agents.stream().map(AgentConfig::id).anyMatch(id -> !ids.add(id))) {
      throw invalid("agent ids must be unique");
    }
    int configuredSlots =
        config.concurrency().researchSlots() + config.concurrency().coordinationSlots();
    int enabledCapacity =
        agents.stream()
            .filter(AgentConfig::enabled)
            .mapToInt(AgentConfig::maxConcurrency)
            .sum();
    if (config.concurrency().enabled()
        && configuredSlots > config.runtime().maxParallelCalls()) {
      throw invalid("concurrency slots cannot exceed runtime.max_parallel_calls");
    }
    if (config.concurrency().enabled() && configuredSlots > enabledCapacity) {
      throw invalid("concurrency slots cannot exceed enabled agent capacity");
    }
    if (config.budget().maxCostUsd() != null) {
      agents.stream()
          .filter(AgentConfig::enabled)
          .filter(agent -> !"mock".equals(agent.provider()))
          .filter(
              agent ->
                  agent.pricing().inputPerMillion() <= 0.0d
                      || agent.pricing().outputPerMillion() <= 0.0d)
          .findFirst()
          .ifPresent(
              agent -> {
                throw invalid(
                    "agent "
                        + agent.id()
                        + ": UNPRICED_PROVIDER while budget.max_cost_usd is active");
              });
    }

    TopologyConfig topology = config.topology();
    boolean activeHierarchical =
        "hierarchical_sparse".equals(topology.mode())
            && (topology.typedCommunication().enabled()
                || topology.routeTeams().enabled()
                || "active".equals(topology.proofGraph().mode())
                || topology.inspiration().enabled()
                    && "active".equals(topology.inspiration().mode()));
    if (activeHierarchical && !config.continuation().enabled()) {
      throw invalid(
          "active hierarchical topology requires continuation.enabled=true "
              + "so Broker, typed route prompts, and RouteTeam execute in the live pipeline");
    }

    InspirationConfig inspiration = topology.inspiration();
    if (!"hierarchical_sparse".equals(topology.mode())
        || !inspiration.enabled()
        || "off".equals(inspiration.mode())
        || !inspiration.protectFinalizationReserve()) {
      return;
    }
    int verificationCalls =
        1
            + config.budget().highRiskVerifierReplicas()
            + config.scheduler().verificationCallSafetyMargin();
    int revisionCycles =
        Math.min(
            config.scheduler().reserveRevisionCycles(),
            config.budget().maxRevisions());
    int requestedReserve =
        1
            + verificationCalls
            + revisionCycles * (1 + verificationCalls)
            + config.scheduler().finishTransitionBufferCalls();
    int reserve = Math.min(config.budget().maxTotalCalls(), requestedReserve);
    int exploratoryCalls = config.budget().maxTotalCalls() - reserve;
    if (inspiration.surpriseBudgetMinCalls() > exploratoryCalls) {
      throw invalid(
          "surprise_budget_min_calls would consume the protected finalization reserve");
    }
  }

  private static void validateHttpStatuses(List<Integer> statuses) {
    if (statuses.stream().anyMatch(value -> value < 400 || value > 599)) {
      throw invalid("provider circuit HTTP statuses must be in 400..599");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static ConfigValidationException invalid(String message) {
    return new ConfigValidationException(message);
  }
}
