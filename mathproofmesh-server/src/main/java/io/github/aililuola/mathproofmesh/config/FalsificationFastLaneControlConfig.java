package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record FalsificationFastLaneControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "exact_arithmetic_only") Boolean exactArithmeticOnly,
    @JsonProperty(value = "max_runtime_seconds") Double maxRuntimeSeconds,
    @JsonProperty(value = "max_memory_mb") Integer maxMemoryMb,
    @JsonProperty(value = "max_cases") Integer maxCases,
    @JsonProperty(value = "max_tasks_per_round") Integer maxTasksPerRound,
    @JsonProperty(value = "allow_sandboxed_python") Boolean allowSandboxedPython,
    @JsonProperty(value = "auto_fact_promotion") Boolean autoFactPromotion,
    @JsonProperty(value = "allowed_purposes") List<ComputationPurpose> allowedPurposes
) implements ConfigModel {

  @JsonCreator
  public FalsificationFastLaneControlConfig(Boolean enabled, Boolean exactArithmeticOnly, Double maxRuntimeSeconds, Integer maxMemoryMb, Integer maxCases, Integer maxTasksPerRound, Boolean allowSandboxedPython, Boolean autoFactPromotion, List<ComputationPurpose> allowedPurposes) {
    if (enabled == null) {
      enabled = true;
    }
    if (exactArithmeticOnly == null) {
      exactArithmeticOnly = true;
    }
    if (maxRuntimeSeconds == null) {
      maxRuntimeSeconds = 10.0d;
    }
    ConfigValidation.minimum("max_runtime_seconds", maxRuntimeSeconds, 0.1d);
    ConfigValidation.maximum("max_runtime_seconds", maxRuntimeSeconds, 120.0d);
    if (maxMemoryMb == null) {
      maxMemoryMb = 256;
    }
    ConfigValidation.minimum("max_memory_mb", maxMemoryMb, 32);
    ConfigValidation.maximum("max_memory_mb", maxMemoryMb, 4096);
    if (maxCases == null) {
      maxCases = 100000;
    }
    ConfigValidation.minimum("max_cases", maxCases, 1);
    ConfigValidation.maximum("max_cases", maxCases, 10000000);
    if (maxTasksPerRound == null) {
      maxTasksPerRound = 2;
    }
    ConfigValidation.minimum("max_tasks_per_round", maxTasksPerRound, 0);
    ConfigValidation.maximum("max_tasks_per_round", maxTasksPerRound, 16);
    if (allowSandboxedPython == null) {
      allowSandboxedPython = false;
    }
    if (autoFactPromotion == null) {
      autoFactPromotion = false;
    }
    if (allowedPurposes == null) {
      allowedPurposes = List.of(ComputationPurpose.FALSIFY_CLAIM, ComputationPurpose.TEST_BOUNDARY_CASES);
    }
    allowedPurposes = ConfigValidation.immutableList("allowed_purposes", allowedPurposes);
    this.enabled = enabled;
    this.exactArithmeticOnly = exactArithmeticOnly;
    this.maxRuntimeSeconds = maxRuntimeSeconds;
    this.maxMemoryMb = maxMemoryMb;
    this.maxCases = maxCases;
    this.maxTasksPerRound = maxTasksPerRound;
    this.allowSandboxedPython = allowSandboxedPython;
    this.autoFactPromotion = autoFactPromotion;
    this.allowedPurposes = allowedPurposes;
  }

  public static FalsificationFastLaneControlConfig defaults() {
    return new FalsificationFastLaneControlConfig(null, null, null, null, null, null, null, null, null);
  }

  @JsonProperty("allowed_purposes")
  @Override
  public List<ComputationPurpose> allowedPurposes() {
    return allowedPurposes == null ? null : List.copyOf(allowedPurposes);
  }

}
