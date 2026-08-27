package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RuntimeConfig(
    @JsonProperty(value = "project_root") String projectRoot,
    @JsonProperty(value = "run_root") String runRoot,
    @JsonProperty(value = "output_language") String outputLanguage,
    @JsonProperty(value = "max_parallel_calls") Integer maxParallelCalls,
    @JsonProperty(value = "parse_retries") Integer parseRetries,
    @JsonProperty(value = "request_retries") Integer requestRetries,
    @JsonProperty(value = "two_phase_output") Boolean twoPhaseOutput,
    @JsonProperty(value = "two_phase_stages") List<String> twoPhaseStages,
    @JsonProperty(value = "checkpoint_every_stage") Boolean checkpointEveryStage,
    @JsonProperty(value = "save_raw_provider_responses") Boolean saveRawProviderResponses,
    @JsonProperty(value = "redact_prompts_in_console") Boolean redactPromptsInConsole,
    @JsonProperty(value = "activity_mode") String activityMode,
    @JsonProperty(value = "activity_max_visible") Integer activityMaxVisible,
    @JsonProperty(value = "activity_persist") Boolean activityPersist,
    @JsonProperty(value = "activity_include_agent_calls") Boolean activityIncludeAgentCalls,
    @JsonProperty(value = "activity_heartbeat_seconds") Double activityHeartbeatSeconds,
    @JsonProperty(value = "stream_first_chunk_timeout_seconds") Double streamFirstChunkTimeoutSeconds,
    @JsonProperty(value = "stream_idle_timeout_seconds") Double streamIdleTimeoutSeconds,
    @JsonProperty(value = "agent_call_wall_timeout_seconds") Double agentCallWallTimeoutSeconds,
    @JsonProperty(value = "json_repair_max_output_tokens") Integer jsonRepairMaxOutputTokens,
    @JsonProperty(value = "stage_output_token_limits") Map<String, Integer> stageOutputTokenLimits,
    @JsonProperty(value = "stage_thinking_modes") Map<String, String> stageThinkingModes,
    @JsonProperty(value = "exploration_output_token_tiers") List<Integer> explorationOutputTokenTiers,
    @JsonProperty(value = "provider_circuit_breaker_enabled") Boolean providerCircuitBreakerEnabled,
    @JsonProperty(value = "provider_circuit_failure_threshold") Integer providerCircuitFailureThreshold,
    @JsonProperty(value = "provider_circuit_window_seconds") Double providerCircuitWindowSeconds,
    @JsonProperty(value = "provider_circuit_cooldown_seconds") Double providerCircuitCooldownSeconds,
    @JsonProperty(value = "provider_terminal_http_statuses") List<Integer> providerTerminalHttpStatuses,
    @JsonProperty(value = "provider_shared_auth_http_statuses") List<Integer> providerSharedAuthHttpStatuses,
    @JsonProperty(value = "log_level") String logLevel,
    @JsonProperty(value = "random_seed") Integer randomSeed
) implements ConfigModel {

  @JsonCreator
  public RuntimeConfig(String projectRoot, String runRoot, String outputLanguage, Integer maxParallelCalls, Integer parseRetries, Integer requestRetries, Boolean twoPhaseOutput, List<String> twoPhaseStages, Boolean checkpointEveryStage, Boolean saveRawProviderResponses, Boolean redactPromptsInConsole, String activityMode, Integer activityMaxVisible, Boolean activityPersist, Boolean activityIncludeAgentCalls, Double activityHeartbeatSeconds, Double streamFirstChunkTimeoutSeconds, Double streamIdleTimeoutSeconds, Double agentCallWallTimeoutSeconds, Integer jsonRepairMaxOutputTokens, Map<String, Integer> stageOutputTokenLimits, Map<String, String> stageThinkingModes, List<Integer> explorationOutputTokenTiers, Boolean providerCircuitBreakerEnabled, Integer providerCircuitFailureThreshold, Double providerCircuitWindowSeconds, Double providerCircuitCooldownSeconds, List<Integer> providerTerminalHttpStatuses, List<Integer> providerSharedAuthHttpStatuses, String logLevel, Integer randomSeed) {
    if (projectRoot == null) {
      projectRoot = ".";
    }
    projectRoot = ConfigValidation.trim(projectRoot);
    if (runRoot == null) {
      runRoot = "runs";
    }
    runRoot = ConfigValidation.trim(runRoot);
    if (outputLanguage == null) {
      outputLanguage = "zh-CN";
    }
    outputLanguage = ConfigValidation.trim(outputLanguage);
    if (maxParallelCalls == null) {
      maxParallelCalls = 8;
    }
    ConfigValidation.minimum("max_parallel_calls", maxParallelCalls, 1);
    ConfigValidation.maximum("max_parallel_calls", maxParallelCalls, 128);
    if (parseRetries == null) {
      parseRetries = 1;
    }
    ConfigValidation.minimum("parse_retries", parseRetries, 0);
    ConfigValidation.maximum("parse_retries", parseRetries, 5);
    if (requestRetries == null) {
      requestRetries = 3;
    }
    ConfigValidation.minimum("request_retries", requestRetries, 0);
    ConfigValidation.maximum("request_retries", requestRetries, 10);
    if (twoPhaseOutput == null) {
      twoPhaseOutput = false;
    }
    if (twoPhaseStages == null) {
      twoPhaseStages = List.of("independent_exploration", "proof_continuation");
    }
    twoPhaseStages = ConfigValidation.trimStrings("two_phase_stages", twoPhaseStages);
    if (checkpointEveryStage == null) {
      checkpointEveryStage = true;
    }
    if (saveRawProviderResponses == null) {
      saveRawProviderResponses = true;
    }
    if (redactPromptsInConsole == null) {
      redactPromptsInConsole = true;
    }
    if (activityMode == null) {
      activityMode = "compact";
    }
    activityMode = ConfigValidation.trim(activityMode);
    ConfigValidation.oneOf("activity_mode", activityMode, "off", "compact", "detailed");
    if (activityMaxVisible == null) {
      activityMaxVisible = 18;
    }
    ConfigValidation.minimum("activity_max_visible", activityMaxVisible, 4);
    ConfigValidation.maximum("activity_max_visible", activityMaxVisible, 200);
    if (activityPersist == null) {
      activityPersist = true;
    }
    if (activityIncludeAgentCalls == null) {
      activityIncludeAgentCalls = true;
    }
    if (activityHeartbeatSeconds == null) {
      activityHeartbeatSeconds = 20.0d;
    }
    ConfigValidation.minimum("activity_heartbeat_seconds", activityHeartbeatSeconds, 0.0d);
    ConfigValidation.maximum("activity_heartbeat_seconds", activityHeartbeatSeconds, 600.0d);
    if (streamFirstChunkTimeoutSeconds == null) {
      streamFirstChunkTimeoutSeconds = 90.0d;
    }
    ConfigValidation.minimum("stream_first_chunk_timeout_seconds", streamFirstChunkTimeoutSeconds, 5.0d);
    ConfigValidation.maximum("stream_first_chunk_timeout_seconds", streamFirstChunkTimeoutSeconds, 3600.0d);
    if (streamIdleTimeoutSeconds == null) {
      streamIdleTimeoutSeconds = 300.0d;
    }
    ConfigValidation.minimum("stream_idle_timeout_seconds", streamIdleTimeoutSeconds, 5.0d);
    ConfigValidation.maximum("stream_idle_timeout_seconds", streamIdleTimeoutSeconds, 3600.0d);
    if (agentCallWallTimeoutSeconds == null) {
      agentCallWallTimeoutSeconds = 7200.0d;
    }
    ConfigValidation.minimum("agent_call_wall_timeout_seconds", agentCallWallTimeoutSeconds, 5.0d);
    ConfigValidation.maximum("agent_call_wall_timeout_seconds", agentCallWallTimeoutSeconds, 7200.0d);
    if (jsonRepairMaxOutputTokens == null) {
      jsonRepairMaxOutputTokens = 8192;
    }
    ConfigValidation.minimum("json_repair_max_output_tokens", jsonRepairMaxOutputTokens, 256);
    ConfigValidation.maximum("json_repair_max_output_tokens", jsonRepairMaxOutputTokens, 384000);
    if (stageOutputTokenLimits == null) {
      stageOutputTokenLimits = Map.ofEntries(Map.entry("goal_normalization", 4096), Map.entry("triage", 12000), Map.entry("strategy_generation", 24000), Map.entry("claim_extraction", 12000), Map.entry("structural_verification", 16000), Map.entry("checkpoint_verification", 24000), Map.entry("detailed_verification", 32000), Map.entry("final_verification", 32000), Map.entry("blind_structural_verification", 16000), Map.entry("blind_detailed_verification", 32000), Map.entry("meta_review", 16000), Map.entry("route_skeptic", 24000), Map.entry("route_referee", 16000), Map.entry("route_tool_audit", 16000), Map.entry("representation_switchboard", 24000), Map.entry("structural_analogy_search", 24000), Map.entry("invent_auxiliary_construction", 24000), Map.entry("hypothesize_invariant", 24000), Map.entry("reverse_goal_analysis", 24000), Map.entry("persistent_meta_strategy", 24000), Map.entry("surprise_exploration", 24000), Map.entry("inspiration_referee", 16000), Map.entry("post_failure_bottleneck", 16000), Map.entry("synthesis", 64000), Map.entry("final_revision", 64000), Map.entry("experiment_codegen", 12000), Map.entry("computation_contract_repair", 8192), Map.entry("pattern_conjecture_completion", 4096));
    }
    stageOutputTokenLimits = ConfigValidation.immutableMap("stage_output_token_limits", stageOutputTokenLimits);
    if (stageThinkingModes == null) {
      stageThinkingModes = Map.ofEntries(Map.entry("goal_normalization", "disabled"), Map.entry("triage", "disabled"), Map.entry("claim_extraction", "disabled"), Map.entry("post_failure_bottleneck", "disabled"), Map.entry("computation_contract_repair", "disabled"), Map.entry("pattern_conjecture_completion", "disabled"), Map.entry("strategy_generation", "high"), Map.entry("independent_exploration", "tiered"), Map.entry("proof_continuation", "tiered"), Map.entry("route_prove", "tiered"), Map.entry("structural_verification", "high"), Map.entry("checkpoint_verification", "high"), Map.entry("detailed_verification", "high"), Map.entry("final_verification", "high"), Map.entry("blind_structural_verification", "high"), Map.entry("blind_detailed_verification", "high"), Map.entry("meta_review", "high"), Map.entry("route_skeptic", "high"), Map.entry("route_referee", "high"), Map.entry("route_tool_audit", "high"), Map.entry("synthesis", "high"), Map.entry("final_revision", "high"));
    }
    stageThinkingModes = ConfigValidation.trimStringMap("stage_thinking_modes", stageThinkingModes);
    ConfigValidation.mapValuesOneOf("stage_thinking_modes", stageThinkingModes, "agent_default", "disabled", "high", "max", "tiered");
    if (explorationOutputTokenTiers == null) {
      explorationOutputTokenTiers = List.of(64000, 96000, 128000);
    }
    explorationOutputTokenTiers = ConfigValidation.immutableList("exploration_output_token_tiers", explorationOutputTokenTiers);
    ConfigValidation.minimumLength("exploration_output_token_tiers", explorationOutputTokenTiers, 1);
    ConfigValidation.maximumLength("exploration_output_token_tiers", explorationOutputTokenTiers, 8);
    if (providerCircuitBreakerEnabled == null) {
      providerCircuitBreakerEnabled = true;
    }
    if (providerCircuitFailureThreshold == null) {
      providerCircuitFailureThreshold = 2;
    }
    ConfigValidation.minimum("provider_circuit_failure_threshold", providerCircuitFailureThreshold, 2);
    ConfigValidation.maximum("provider_circuit_failure_threshold", providerCircuitFailureThreshold, 32);
    if (providerCircuitWindowSeconds == null) {
      providerCircuitWindowSeconds = 90.0d;
    }
    ConfigValidation.minimum("provider_circuit_window_seconds", providerCircuitWindowSeconds, 1.0d);
    ConfigValidation.maximum("provider_circuit_window_seconds", providerCircuitWindowSeconds, 3600.0d);
    if (providerCircuitCooldownSeconds == null) {
      providerCircuitCooldownSeconds = 300.0d;
    }
    ConfigValidation.minimum("provider_circuit_cooldown_seconds", providerCircuitCooldownSeconds, 1.0d);
    ConfigValidation.maximum("provider_circuit_cooldown_seconds", providerCircuitCooldownSeconds, 86400.0d);
    if (providerTerminalHttpStatuses == null) {
      providerTerminalHttpStatuses = List.of(402);
    }
    providerTerminalHttpStatuses = ConfigValidation.immutableList("provider_terminal_http_statuses", providerTerminalHttpStatuses);
    providerTerminalHttpStatuses = ConfigValidation.sortedDistinct("provider_terminal_http_statuses", providerTerminalHttpStatuses);
    if (providerSharedAuthHttpStatuses == null) {
      providerSharedAuthHttpStatuses = List.of(401, 403);
    }
    providerSharedAuthHttpStatuses = ConfigValidation.immutableList("provider_shared_auth_http_statuses", providerSharedAuthHttpStatuses);
    providerSharedAuthHttpStatuses = ConfigValidation.sortedDistinct("provider_shared_auth_http_statuses", providerSharedAuthHttpStatuses);
    if (logLevel == null) {
      logLevel = "INFO";
    }
    logLevel = ConfigValidation.trim(logLevel);
    ConfigValidation.oneOf("log_level", logLevel, "DEBUG", "INFO", "WARNING", "ERROR");
    if (randomSeed == null) {
      randomSeed = 20260719;
    }
    this.projectRoot = projectRoot;
    this.runRoot = runRoot;
    this.outputLanguage = outputLanguage;
    this.maxParallelCalls = maxParallelCalls;
    this.parseRetries = parseRetries;
    this.requestRetries = requestRetries;
    this.twoPhaseOutput = twoPhaseOutput;
    this.twoPhaseStages = twoPhaseStages;
    this.checkpointEveryStage = checkpointEveryStage;
    this.saveRawProviderResponses = saveRawProviderResponses;
    this.redactPromptsInConsole = redactPromptsInConsole;
    this.activityMode = activityMode;
    this.activityMaxVisible = activityMaxVisible;
    this.activityPersist = activityPersist;
    this.activityIncludeAgentCalls = activityIncludeAgentCalls;
    this.activityHeartbeatSeconds = activityHeartbeatSeconds;
    this.streamFirstChunkTimeoutSeconds = streamFirstChunkTimeoutSeconds;
    this.streamIdleTimeoutSeconds = streamIdleTimeoutSeconds;
    this.agentCallWallTimeoutSeconds = agentCallWallTimeoutSeconds;
    this.jsonRepairMaxOutputTokens = jsonRepairMaxOutputTokens;
    this.stageOutputTokenLimits = stageOutputTokenLimits;
    this.stageThinkingModes = stageThinkingModes;
    this.explorationOutputTokenTiers = explorationOutputTokenTiers;
    this.providerCircuitBreakerEnabled = providerCircuitBreakerEnabled;
    this.providerCircuitFailureThreshold = providerCircuitFailureThreshold;
    this.providerCircuitWindowSeconds = providerCircuitWindowSeconds;
    this.providerCircuitCooldownSeconds = providerCircuitCooldownSeconds;
    this.providerTerminalHttpStatuses = providerTerminalHttpStatuses;
    this.providerSharedAuthHttpStatuses = providerSharedAuthHttpStatuses;
    this.logLevel = logLevel;
    this.randomSeed = randomSeed;
    ConfigInvariants.validate(this);
  }

  public static RuntimeConfig defaults() {
    return new RuntimeConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  @JsonProperty("two_phase_stages")
  @Override
  public List<String> twoPhaseStages() {
    return twoPhaseStages == null ? null : List.copyOf(twoPhaseStages);
  }

  @JsonProperty("stage_output_token_limits")
  @Override
  public Map<String, Integer> stageOutputTokenLimits() {
    return stageOutputTokenLimits == null ? null : Map.copyOf(stageOutputTokenLimits);
  }

  @JsonProperty("stage_thinking_modes")
  @Override
  public Map<String, String> stageThinkingModes() {
    return stageThinkingModes == null ? null : Map.copyOf(stageThinkingModes);
  }

  @JsonProperty("exploration_output_token_tiers")
  @Override
  public List<Integer> explorationOutputTokenTiers() {
    return explorationOutputTokenTiers == null ? null : List.copyOf(explorationOutputTokenTiers);
  }

  @JsonProperty("provider_terminal_http_statuses")
  @Override
  public List<Integer> providerTerminalHttpStatuses() {
    return providerTerminalHttpStatuses == null ? null : List.copyOf(providerTerminalHttpStatuses);
  }

  @JsonProperty("provider_shared_auth_http_statuses")
  @Override
  public List<Integer> providerSharedAuthHttpStatuses() {
    return providerSharedAuthHttpStatuses == null ? null : List.copyOf(providerSharedAuthHttpStatuses);
  }

}
