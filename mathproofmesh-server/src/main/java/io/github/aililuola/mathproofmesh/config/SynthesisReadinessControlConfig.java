package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record SynthesisReadinessControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "mode") String mode,
    @JsonProperty(value = "require_core_dependency_closure") Boolean requireCoreDependencyClosure,
    @JsonProperty(value = "require_no_open_scope_risks") Boolean requireNoOpenScopeRisks,
    @JsonProperty(value = "require_no_unresolved_high_centrality_conflicts") Boolean requireNoUnresolvedHighCentralityConflicts,
    @JsonProperty(value = "require_no_necessary_only_bridge_as_sufficient") Boolean requireNoNecessaryOnlyBridgeAsSufficient,
    @JsonProperty(value = "max_open_auxiliary_obligations") Integer maxOpenAuxiliaryObligations,
    @JsonProperty(value = "high_centrality_threshold") Double highCentralityThreshold,
    @JsonProperty(value = "produce_progress_report_when_blocked") Boolean produceProgressReportWhenBlocked
) implements ConfigModel {

  @JsonCreator
  public SynthesisReadinessControlConfig(Boolean enabled, String mode, Boolean requireCoreDependencyClosure, Boolean requireNoOpenScopeRisks, Boolean requireNoUnresolvedHighCentralityConflicts, Boolean requireNoNecessaryOnlyBridgeAsSufficient, Integer maxOpenAuxiliaryObligations, Double highCentralityThreshold, Boolean produceProgressReportWhenBlocked) {
    if (enabled == null) {
      enabled = true;
    }
    if (mode == null) {
      mode = "shadow";
    }
    mode = ConfigValidation.trim(mode);
    ConfigValidation.oneOf("mode", mode, "off", "shadow", "active");
    if (requireCoreDependencyClosure == null) {
      requireCoreDependencyClosure = true;
    }
    if (requireNoOpenScopeRisks == null) {
      requireNoOpenScopeRisks = true;
    }
    if (requireNoUnresolvedHighCentralityConflicts == null) {
      requireNoUnresolvedHighCentralityConflicts = true;
    }
    if (requireNoNecessaryOnlyBridgeAsSufficient == null) {
      requireNoNecessaryOnlyBridgeAsSufficient = true;
    }
    if (maxOpenAuxiliaryObligations == null) {
      maxOpenAuxiliaryObligations = 16;
    }
    ConfigValidation.minimum("max_open_auxiliary_obligations", maxOpenAuxiliaryObligations, 0);
    ConfigValidation.maximum("max_open_auxiliary_obligations", maxOpenAuxiliaryObligations, 10000);
    if (highCentralityThreshold == null) {
      highCentralityThreshold = 0.7d;
    }
    ConfigValidation.minimum("high_centrality_threshold", highCentralityThreshold, 0.0d);
    ConfigValidation.maximum("high_centrality_threshold", highCentralityThreshold, 1.0d);
    if (produceProgressReportWhenBlocked == null) {
      produceProgressReportWhenBlocked = true;
    }
    this.enabled = enabled;
    this.mode = mode;
    this.requireCoreDependencyClosure = requireCoreDependencyClosure;
    this.requireNoOpenScopeRisks = requireNoOpenScopeRisks;
    this.requireNoUnresolvedHighCentralityConflicts = requireNoUnresolvedHighCentralityConflicts;
    this.requireNoNecessaryOnlyBridgeAsSufficient = requireNoNecessaryOnlyBridgeAsSufficient;
    this.maxOpenAuxiliaryObligations = maxOpenAuxiliaryObligations;
    this.highCentralityThreshold = highCentralityThreshold;
    this.produceProgressReportWhenBlocked = produceProgressReportWhenBlocked;
  }

  public static SynthesisReadinessControlConfig defaults() {
    return new SynthesisReadinessControlConfig(null, null, null, null, null, null, null, null, null);
  }
}
