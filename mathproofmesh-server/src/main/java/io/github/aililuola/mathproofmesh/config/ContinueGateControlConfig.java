package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ContinueGateControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "mode") String mode,
    @JsonProperty(value = "no_core_progress_segments") Integer noCoreProgressSegments,
    @JsonProperty(value = "require_any_core_signal") Boolean requireAnyCoreSignal,
    @JsonProperty(value = "allow_on_core_obligation_closed") Boolean allowOnCoreObligationClosed,
    @JsonProperty(value = "allow_on_core_debt_reduced") Boolean allowOnCoreDebtReduced,
    @JsonProperty(value = "allow_on_first_error_changed") Boolean allowOnFirstErrorChanged,
    @JsonProperty(value = "allow_on_verified_bridge_gain") Boolean allowOnVerifiedBridgeGain,
    @JsonProperty(value = "force_blueprint_rewrite_after_block") Boolean forceBlueprintRewriteAfterBlock
) implements ConfigModel {

  @JsonCreator
  public ContinueGateControlConfig(Boolean enabled, String mode, Integer noCoreProgressSegments, Boolean requireAnyCoreSignal, Boolean allowOnCoreObligationClosed, Boolean allowOnCoreDebtReduced, Boolean allowOnFirstErrorChanged, Boolean allowOnVerifiedBridgeGain, Boolean forceBlueprintRewriteAfterBlock) {
    if (enabled == null) {
      enabled = true;
    }
    if (mode == null) {
      mode = "shadow";
    }
    mode = ConfigValidation.trim(mode);
    ConfigValidation.oneOf("mode", mode, "off", "shadow", "active");
    if (noCoreProgressSegments == null) {
      noCoreProgressSegments = 2;
    }
    ConfigValidation.minimum("no_core_progress_segments", noCoreProgressSegments, 1);
    ConfigValidation.maximum("no_core_progress_segments", noCoreProgressSegments, 16);
    if (requireAnyCoreSignal == null) {
      requireAnyCoreSignal = true;
    }
    if (allowOnCoreObligationClosed == null) {
      allowOnCoreObligationClosed = true;
    }
    if (allowOnCoreDebtReduced == null) {
      allowOnCoreDebtReduced = true;
    }
    if (allowOnFirstErrorChanged == null) {
      allowOnFirstErrorChanged = true;
    }
    if (allowOnVerifiedBridgeGain == null) {
      allowOnVerifiedBridgeGain = true;
    }
    if (forceBlueprintRewriteAfterBlock == null) {
      forceBlueprintRewriteAfterBlock = true;
    }
    this.enabled = enabled;
    this.mode = mode;
    this.noCoreProgressSegments = noCoreProgressSegments;
    this.requireAnyCoreSignal = requireAnyCoreSignal;
    this.allowOnCoreObligationClosed = allowOnCoreObligationClosed;
    this.allowOnCoreDebtReduced = allowOnCoreDebtReduced;
    this.allowOnFirstErrorChanged = allowOnFirstErrorChanged;
    this.allowOnVerifiedBridgeGain = allowOnVerifiedBridgeGain;
    this.forceBlueprintRewriteAfterBlock = forceBlueprintRewriteAfterBlock;
  }

  public static ContinueGateControlConfig defaults() {
    return new ContinueGateControlConfig(null, null, null, null, null, null, null, null, null);
  }
}
