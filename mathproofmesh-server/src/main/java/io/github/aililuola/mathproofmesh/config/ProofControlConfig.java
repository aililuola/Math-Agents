package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ProofControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "mode") String mode,
    @JsonProperty(value = "strict_fail_closed") Boolean strictFailClosed,
    @JsonProperty(value = "goal_alignment") GoalAlignmentControlConfig goalAlignment,
    @JsonProperty(value = "scope_guard") ScopeGuardControlConfig scopeGuard,
    @JsonProperty(value = "core_debt") CoreDebtControlConfig coreDebt,
    @JsonProperty(value = "realizer") RealizerControlConfig realizer,
    @JsonProperty(value = "induction") InductionControlConfig induction,
    @JsonProperty(value = "failure") FailureControlConfig failure,
    @JsonProperty(value = "bottleneck") BottleneckControlConfig bottleneck,
    @JsonProperty(value = "common_mode") CommonModeControlConfig commonMode,
    @JsonProperty(value = "message_utility") MessageUtilityControlConfig messageUtility,
    @JsonProperty(value = "near_miss") NearMissControlConfig nearMiss,
    @JsonProperty(value = "falsification_fast_lane") FalsificationFastLaneControlConfig falsificationFastLane,
    @JsonProperty(value = "route_admission") RouteAdmissionControlConfig routeAdmission,
    @JsonProperty(value = "continue_gate") ContinueGateControlConfig continueGate,
    @JsonProperty(value = "synthesis_readiness") SynthesisReadinessControlConfig synthesisReadiness
) implements ConfigModel {

  @JsonCreator
  public ProofControlConfig(Boolean enabled, String mode, Boolean strictFailClosed, GoalAlignmentControlConfig goalAlignment, ScopeGuardControlConfig scopeGuard, CoreDebtControlConfig coreDebt, RealizerControlConfig realizer, InductionControlConfig induction, FailureControlConfig failure, BottleneckControlConfig bottleneck, CommonModeControlConfig commonMode, MessageUtilityControlConfig messageUtility, NearMissControlConfig nearMiss, FalsificationFastLaneControlConfig falsificationFastLane, RouteAdmissionControlConfig routeAdmission, ContinueGateControlConfig continueGate, SynthesisReadinessControlConfig synthesisReadiness) {
    if (enabled == null) {
      enabled = false;
    }
    if (mode == null) {
      mode = "off";
    }
    mode = ConfigValidation.trim(mode);
    ConfigValidation.oneOf("mode", mode, "off", "shadow", "active");
    if (strictFailClosed == null) {
      strictFailClosed = true;
    }
    if (goalAlignment == null) {
      goalAlignment = GoalAlignmentControlConfig.defaults();
    }
    if (scopeGuard == null) {
      scopeGuard = ScopeGuardControlConfig.defaults();
    }
    if (coreDebt == null) {
      coreDebt = CoreDebtControlConfig.defaults();
    }
    if (realizer == null) {
      realizer = RealizerControlConfig.defaults();
    }
    if (induction == null) {
      induction = InductionControlConfig.defaults();
    }
    if (failure == null) {
      failure = FailureControlConfig.defaults();
    }
    if (bottleneck == null) {
      bottleneck = BottleneckControlConfig.defaults();
    }
    if (commonMode == null) {
      commonMode = CommonModeControlConfig.defaults();
    }
    if (messageUtility == null) {
      messageUtility = MessageUtilityControlConfig.defaults();
    }
    if (nearMiss == null) {
      nearMiss = NearMissControlConfig.defaults();
    }
    if (falsificationFastLane == null) {
      falsificationFastLane = FalsificationFastLaneControlConfig.defaults();
    }
    if (routeAdmission == null) {
      routeAdmission = RouteAdmissionControlConfig.defaults();
    }
    if (continueGate == null) {
      continueGate = ContinueGateControlConfig.defaults();
    }
    if (synthesisReadiness == null) {
      synthesisReadiness = SynthesisReadinessControlConfig.defaults();
    }
    this.enabled = enabled;
    this.mode = mode;
    this.strictFailClosed = strictFailClosed;
    this.goalAlignment = goalAlignment;
    this.scopeGuard = scopeGuard;
    this.coreDebt = coreDebt;
    this.realizer = realizer;
    this.induction = induction;
    this.failure = failure;
    this.bottleneck = bottleneck;
    this.commonMode = commonMode;
    this.messageUtility = messageUtility;
    this.nearMiss = nearMiss;
    this.falsificationFastLane = falsificationFastLane;
    this.routeAdmission = routeAdmission;
    this.continueGate = continueGate;
    this.synthesisReadiness = synthesisReadiness;
  }

  public static ProofControlConfig defaults() {
    return new ProofControlConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
