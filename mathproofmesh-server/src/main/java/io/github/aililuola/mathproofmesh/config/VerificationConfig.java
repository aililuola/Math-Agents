package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record VerificationConfig(
    @JsonProperty(value = "structural_first") Boolean structuralFirst,
    @JsonProperty(value = "detailed_only_after_structural_pass") Boolean detailedOnlyAfterStructuralPass,
    @JsonProperty(value = "verify_problem_integrity") Boolean verifyProblemIntegrity,
    @JsonProperty(value = "require_first_error_step") Boolean requireFirstErrorStep,
    @JsonProperty(value = "require_key_step_tagging") Boolean requireKeyStepTagging,
    @JsonProperty(value = "enable_sympy_tools") Boolean enableSympyTools,
    @JsonProperty(value = "enable_numeric_counterexamples") Boolean enableNumericCounterexamples,
    @JsonProperty(value = "enable_lean") Boolean enableLean,
    @JsonProperty(value = "lean_command") String leanCommand,
    @JsonProperty(value = "lean_sandbox_required") Boolean leanSandboxRequired,
    @JsonProperty(value = "lean_sandbox_image") @ConfigNullable String leanSandboxImage,
    @JsonProperty(value = "lean_sandbox_memory_mb") Integer leanSandboxMemoryMb,
    @JsonProperty(value = "lean_sandbox_pids_limit") Integer leanSandboxPidsLimit,
    @JsonProperty(value = "lean_sandbox_cpus") Double leanSandboxCpus,
    @JsonProperty(value = "external_tool_timeout_seconds") Double externalToolTimeoutSeconds
) implements ConfigModel {

  @JsonCreator
  public VerificationConfig(Boolean structuralFirst, Boolean detailedOnlyAfterStructuralPass, Boolean verifyProblemIntegrity, Boolean requireFirstErrorStep, Boolean requireKeyStepTagging, Boolean enableSympyTools, Boolean enableNumericCounterexamples, Boolean enableLean, String leanCommand, Boolean leanSandboxRequired, String leanSandboxImage, Integer leanSandboxMemoryMb, Integer leanSandboxPidsLimit, Double leanSandboxCpus, Double externalToolTimeoutSeconds) {
    if (structuralFirst == null) {
      structuralFirst = true;
    }
    if (detailedOnlyAfterStructuralPass == null) {
      detailedOnlyAfterStructuralPass = true;
    }
    if (verifyProblemIntegrity == null) {
      verifyProblemIntegrity = true;
    }
    if (requireFirstErrorStep == null) {
      requireFirstErrorStep = true;
    }
    if (requireKeyStepTagging == null) {
      requireKeyStepTagging = true;
    }
    if (enableSympyTools == null) {
      enableSympyTools = true;
    }
    if (enableNumericCounterexamples == null) {
      enableNumericCounterexamples = true;
    }
    if (enableLean == null) {
      enableLean = false;
    }
    if (leanCommand == null) {
      leanCommand = "lake env lean";
    }
    leanCommand = ConfigValidation.trim(leanCommand);
    if (leanSandboxRequired == null) {
      leanSandboxRequired = true;
    }
    ConfigValidation.oneOf("lean_sandbox_required", leanSandboxRequired, true);
    leanSandboxImage = ConfigValidation.trim(leanSandboxImage);
    if (leanSandboxMemoryMb == null) {
      leanSandboxMemoryMb = 1024;
    }
    ConfigValidation.minimum("lean_sandbox_memory_mb", leanSandboxMemoryMb, 128);
    ConfigValidation.maximum("lean_sandbox_memory_mb", leanSandboxMemoryMb, 8192);
    if (leanSandboxPidsLimit == null) {
      leanSandboxPidsLimit = 128;
    }
    ConfigValidation.minimum("lean_sandbox_pids_limit", leanSandboxPidsLimit, 16);
    ConfigValidation.maximum("lean_sandbox_pids_limit", leanSandboxPidsLimit, 1024);
    if (leanSandboxCpus == null) {
      leanSandboxCpus = 1.0d;
    }
    ConfigValidation.exclusiveMinimum("lean_sandbox_cpus", leanSandboxCpus, 0.0d);
    ConfigValidation.maximum("lean_sandbox_cpus", leanSandboxCpus, 8.0d);
    if (externalToolTimeoutSeconds == null) {
      externalToolTimeoutSeconds = 20.0d;
    }
    ConfigValidation.minimum("external_tool_timeout_seconds", externalToolTimeoutSeconds, 1.0d);
    ConfigValidation.maximum("external_tool_timeout_seconds", externalToolTimeoutSeconds, 600.0d);
    this.structuralFirst = structuralFirst;
    this.detailedOnlyAfterStructuralPass = detailedOnlyAfterStructuralPass;
    this.verifyProblemIntegrity = verifyProblemIntegrity;
    this.requireFirstErrorStep = requireFirstErrorStep;
    this.requireKeyStepTagging = requireKeyStepTagging;
    this.enableSympyTools = enableSympyTools;
    this.enableNumericCounterexamples = enableNumericCounterexamples;
    this.enableLean = enableLean;
    this.leanCommand = leanCommand;
    this.leanSandboxRequired = leanSandboxRequired;
    this.leanSandboxImage = leanSandboxImage;
    this.leanSandboxMemoryMb = leanSandboxMemoryMb;
    this.leanSandboxPidsLimit = leanSandboxPidsLimit;
    this.leanSandboxCpus = leanSandboxCpus;
    this.externalToolTimeoutSeconds = externalToolTimeoutSeconds;
    ConfigInvariants.validate(this);
  }

  public static VerificationConfig defaults() {
    return new VerificationConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
