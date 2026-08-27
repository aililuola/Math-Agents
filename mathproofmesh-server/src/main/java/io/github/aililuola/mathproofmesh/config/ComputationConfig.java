package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ComputationConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "policy") String policy,
    @JsonProperty(value = "typed_tools_enabled") Boolean typedToolsEnabled,
    @JsonProperty(value = "sandboxed_python_enabled") Boolean sandboxedPythonEnabled,
    @JsonProperty(value = "execute_planner_hints_immediately") Boolean executePlannerHintsImmediately,
    @JsonProperty(value = "critical_calculation_gate_enabled") Boolean criticalCalculationGateEnabled,
    @JsonProperty(value = "critical_calculation_require_declarations") Boolean criticalCalculationRequireDeclarations,
    @JsonProperty(value = "critical_calculation_max_checks_per_artifact") Integer criticalCalculationMaxChecksPerArtifact,
    @JsonProperty(value = "targeted_falsification_fast_path") Boolean targetedFalsificationFastPath,
    @JsonProperty(value = "bounded_typed_probe_fast_path") Boolean boundedTypedProbeFastPath,
    @JsonProperty(value = "bounded_typed_probe_max_cases") Integer boundedTypedProbeMaxCases,
    @JsonProperty(value = "soft_experiments_per_path") Integer softExperimentsPerPath,
    @JsonProperty(value = "hard_experiments_per_path") Integer hardExperimentsPerPath,
    @JsonProperty(value = "max_compute_cycles_per_segment") Integer maxComputeCyclesPerSegment,
    @JsonProperty(value = "max_contract_repairs_per_segment") Integer maxContractRepairsPerSegment,
    @JsonProperty(value = "contract_repair_max_output_tokens") Integer contractRepairMaxOutputTokens,
    @JsonProperty(value = "max_total_cpu_seconds") Double maxTotalCpuSeconds,
    @JsonProperty(value = "max_cases_per_experiment") Integer maxCasesPerExperiment,
    @JsonProperty(value = "max_output_chars") Integer maxOutputChars,
    @JsonProperty(value = "broad_search_after_stalled_rounds") Integer broadSearchAfterStalledRounds,
    @JsonProperty(value = "broad_search_requires_meta_review") Boolean broadSearchRequiresMetaReview,
    @JsonProperty(value = "cache_results") Boolean cacheResults,
    @JsonProperty(value = "sandbox_image") @ConfigNullable String sandboxImage,
    @JsonProperty(value = "sandbox_timeout_seconds") Double sandboxTimeoutSeconds,
    @JsonProperty(value = "sandbox_memory_mb") Integer sandboxMemoryMb,
    @JsonProperty(value = "sandbox_cpus") Double sandboxCpus,
    @JsonProperty(value = "sandbox_pids_limit") Integer sandboxPidsLimit
) implements ConfigModel {

  @JsonCreator
  public ComputationConfig(Boolean enabled, String policy, Boolean typedToolsEnabled, Boolean sandboxedPythonEnabled, Boolean executePlannerHintsImmediately, Boolean criticalCalculationGateEnabled, Boolean criticalCalculationRequireDeclarations, Integer criticalCalculationMaxChecksPerArtifact, Boolean targetedFalsificationFastPath, Boolean boundedTypedProbeFastPath, Integer boundedTypedProbeMaxCases, Integer softExperimentsPerPath, Integer hardExperimentsPerPath, Integer maxComputeCyclesPerSegment, Integer maxContractRepairsPerSegment, Integer contractRepairMaxOutputTokens, Double maxTotalCpuSeconds, Integer maxCasesPerExperiment, Integer maxOutputChars, Integer broadSearchAfterStalledRounds, Boolean broadSearchRequiresMetaReview, Boolean cacheResults, String sandboxImage, Double sandboxTimeoutSeconds, Integer sandboxMemoryMb, Double sandboxCpus, Integer sandboxPidsLimit) {
    if (enabled == null) {
      enabled = false;
    }
    if (policy == null) {
      policy = "reasoning_first";
    }
    policy = ConfigValidation.trim(policy);
    ConfigValidation.oneOf("policy", policy, "reasoning_first");
    if (typedToolsEnabled == null) {
      typedToolsEnabled = true;
    }
    if (sandboxedPythonEnabled == null) {
      sandboxedPythonEnabled = false;
    }
    if (executePlannerHintsImmediately == null) {
      executePlannerHintsImmediately = false;
    }
    if (criticalCalculationGateEnabled == null) {
      criticalCalculationGateEnabled = true;
    }
    if (criticalCalculationRequireDeclarations == null) {
      criticalCalculationRequireDeclarations = true;
    }
    if (criticalCalculationMaxChecksPerArtifact == null) {
      criticalCalculationMaxChecksPerArtifact = 8;
    }
    ConfigValidation.minimum("critical_calculation_max_checks_per_artifact", criticalCalculationMaxChecksPerArtifact, 1);
    ConfigValidation.maximum("critical_calculation_max_checks_per_artifact", criticalCalculationMaxChecksPerArtifact, 64);
    if (targetedFalsificationFastPath == null) {
      targetedFalsificationFastPath = true;
    }
    if (boundedTypedProbeFastPath == null) {
      boundedTypedProbeFastPath = true;
    }
    if (boundedTypedProbeMaxCases == null) {
      boundedTypedProbeMaxCases = 25000;
    }
    ConfigValidation.minimum("bounded_typed_probe_max_cases", boundedTypedProbeMaxCases, 1);
    ConfigValidation.maximum("bounded_typed_probe_max_cases", boundedTypedProbeMaxCases, 1000000);
    if (softExperimentsPerPath == null) {
      softExperimentsPerPath = 2;
    }
    ConfigValidation.minimum("soft_experiments_per_path", softExperimentsPerPath, 0);
    ConfigValidation.maximum("soft_experiments_per_path", softExperimentsPerPath, 100);
    if (hardExperimentsPerPath == null) {
      hardExperimentsPerPath = 6;
    }
    ConfigValidation.minimum("hard_experiments_per_path", hardExperimentsPerPath, 1);
    ConfigValidation.maximum("hard_experiments_per_path", hardExperimentsPerPath, 100);
    if (maxComputeCyclesPerSegment == null) {
      maxComputeCyclesPerSegment = 1;
    }
    ConfigValidation.minimum("max_compute_cycles_per_segment", maxComputeCyclesPerSegment, 0);
    ConfigValidation.maximum("max_compute_cycles_per_segment", maxComputeCyclesPerSegment, 8);
    if (maxContractRepairsPerSegment == null) {
      maxContractRepairsPerSegment = 1;
    }
    ConfigValidation.minimum("max_contract_repairs_per_segment", maxContractRepairsPerSegment, 0);
    ConfigValidation.maximum("max_contract_repairs_per_segment", maxContractRepairsPerSegment, 2);
    if (contractRepairMaxOutputTokens == null) {
      contractRepairMaxOutputTokens = 8192;
    }
    ConfigValidation.minimum("contract_repair_max_output_tokens", contractRepairMaxOutputTokens, 256);
    ConfigValidation.maximum("contract_repair_max_output_tokens", contractRepairMaxOutputTokens, 32000);
    if (maxTotalCpuSeconds == null) {
      maxTotalCpuSeconds = 120.0d;
    }
    ConfigValidation.minimum("max_total_cpu_seconds", maxTotalCpuSeconds, 0.1d);
    ConfigValidation.maximum("max_total_cpu_seconds", maxTotalCpuSeconds, 86400.0d);
    if (maxCasesPerExperiment == null) {
      maxCasesPerExperiment = 1000000;
    }
    ConfigValidation.minimum("max_cases_per_experiment", maxCasesPerExperiment, 1);
    ConfigValidation.maximum("max_cases_per_experiment", maxCasesPerExperiment, 100000000);
    if (maxOutputChars == null) {
      maxOutputChars = 20000;
    }
    ConfigValidation.minimum("max_output_chars", maxOutputChars, 256);
    ConfigValidation.maximum("max_output_chars", maxOutputChars, 2000000);
    if (broadSearchAfterStalledRounds == null) {
      broadSearchAfterStalledRounds = 1;
    }
    ConfigValidation.minimum("broad_search_after_stalled_rounds", broadSearchAfterStalledRounds, 0);
    ConfigValidation.maximum("broad_search_after_stalled_rounds", broadSearchAfterStalledRounds, 64);
    if (broadSearchRequiresMetaReview == null) {
      broadSearchRequiresMetaReview = true;
    }
    if (cacheResults == null) {
      cacheResults = true;
    }
    sandboxImage = ConfigValidation.trim(sandboxImage);
    if (sandboxTimeoutSeconds == null) {
      sandboxTimeoutSeconds = 20.0d;
    }
    ConfigValidation.minimum("sandbox_timeout_seconds", sandboxTimeoutSeconds, 0.1d);
    ConfigValidation.maximum("sandbox_timeout_seconds", sandboxTimeoutSeconds, 600.0d);
    if (sandboxMemoryMb == null) {
      sandboxMemoryMb = 256;
    }
    ConfigValidation.minimum("sandbox_memory_mb", sandboxMemoryMb, 32);
    ConfigValidation.maximum("sandbox_memory_mb", sandboxMemoryMb, 8192);
    if (sandboxCpus == null) {
      sandboxCpus = 1.0d;
    }
    ConfigValidation.minimum("sandbox_cpus", sandboxCpus, 0.1d);
    ConfigValidation.maximum("sandbox_cpus", sandboxCpus, 16.0d);
    if (sandboxPidsLimit == null) {
      sandboxPidsLimit = 32;
    }
    ConfigValidation.minimum("sandbox_pids_limit", sandboxPidsLimit, 4);
    ConfigValidation.maximum("sandbox_pids_limit", sandboxPidsLimit, 1024);
    this.enabled = enabled;
    this.policy = policy;
    this.typedToolsEnabled = typedToolsEnabled;
    this.sandboxedPythonEnabled = sandboxedPythonEnabled;
    this.executePlannerHintsImmediately = executePlannerHintsImmediately;
    this.criticalCalculationGateEnabled = criticalCalculationGateEnabled;
    this.criticalCalculationRequireDeclarations = criticalCalculationRequireDeclarations;
    this.criticalCalculationMaxChecksPerArtifact = criticalCalculationMaxChecksPerArtifact;
    this.targetedFalsificationFastPath = targetedFalsificationFastPath;
    this.boundedTypedProbeFastPath = boundedTypedProbeFastPath;
    this.boundedTypedProbeMaxCases = boundedTypedProbeMaxCases;
    this.softExperimentsPerPath = softExperimentsPerPath;
    this.hardExperimentsPerPath = hardExperimentsPerPath;
    this.maxComputeCyclesPerSegment = maxComputeCyclesPerSegment;
    this.maxContractRepairsPerSegment = maxContractRepairsPerSegment;
    this.contractRepairMaxOutputTokens = contractRepairMaxOutputTokens;
    this.maxTotalCpuSeconds = maxTotalCpuSeconds;
    this.maxCasesPerExperiment = maxCasesPerExperiment;
    this.maxOutputChars = maxOutputChars;
    this.broadSearchAfterStalledRounds = broadSearchAfterStalledRounds;
    this.broadSearchRequiresMetaReview = broadSearchRequiresMetaReview;
    this.cacheResults = cacheResults;
    this.sandboxImage = sandboxImage;
    this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
    this.sandboxMemoryMb = sandboxMemoryMb;
    this.sandboxCpus = sandboxCpus;
    this.sandboxPidsLimit = sandboxPidsLimit;
    ConfigInvariants.validate(this);
  }

  public static ComputationConfig defaults() {
    return new ComputationConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  public ComputationConfig withSandboxedPythonEnabled(boolean enabledValue) {
    return new ComputationConfig(
        enabled,
        policy,
        typedToolsEnabled,
        enabledValue,
        executePlannerHintsImmediately,
        criticalCalculationGateEnabled,
        criticalCalculationRequireDeclarations,
        criticalCalculationMaxChecksPerArtifact,
        targetedFalsificationFastPath,
        boundedTypedProbeFastPath,
        boundedTypedProbeMaxCases,
        softExperimentsPerPath,
        hardExperimentsPerPath,
        maxComputeCyclesPerSegment,
        maxContractRepairsPerSegment,
        contractRepairMaxOutputTokens,
        maxTotalCpuSeconds,
        maxCasesPerExperiment,
        maxOutputChars,
        broadSearchAfterStalledRounds,
        broadSearchRequiresMetaReview,
        cacheResults,
        sandboxImage,
        sandboxTimeoutSeconds,
        sandboxMemoryMb,
        sandboxCpus,
        sandboxPidsLimit);
  }
}
