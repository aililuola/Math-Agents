package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RouteTeamConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "max_members_per_route") Integer maxMembersPerRoute,
    @JsonProperty(value = "local_review_rounds") Integer localReviewRounds,
    @JsonProperty(value = "skeptic_on_high_risk_only") Boolean skepticOnHighRiskOnly,
    @JsonProperty(value = "skeptic_risk_threshold") Double skepticRiskThreshold,
    @JsonProperty(value = "tool_agent_on_demand") Boolean toolAgentOnDemand,
    @JsonProperty(value = "referee_required_before_global_share") Boolean refereeRequiredBeforeGlobalShare,
    @JsonProperty(value = "referee_must_differ_from_author") Boolean refereeMustDifferFromAuthor,
    @JsonProperty(value = "allow_role_reuse_after_stage") Boolean allowRoleReuseAfterStage
) implements ConfigModel {

  @JsonCreator
  public RouteTeamConfig(Boolean enabled, Integer maxMembersPerRoute, Integer localReviewRounds, Boolean skepticOnHighRiskOnly, Double skepticRiskThreshold, Boolean toolAgentOnDemand, Boolean refereeRequiredBeforeGlobalShare, Boolean refereeMustDifferFromAuthor, Boolean allowRoleReuseAfterStage) {
    if (enabled == null) {
      enabled = false;
    }
    if (maxMembersPerRoute == null) {
      maxMembersPerRoute = 3;
    }
    ConfigValidation.minimum("max_members_per_route", maxMembersPerRoute, 1);
    ConfigValidation.maximum("max_members_per_route", maxMembersPerRoute, 8);
    if (localReviewRounds == null) {
      localReviewRounds = 1;
    }
    ConfigValidation.minimum("local_review_rounds", localReviewRounds, 0);
    ConfigValidation.maximum("local_review_rounds", localReviewRounds, 8);
    if (skepticOnHighRiskOnly == null) {
      skepticOnHighRiskOnly = true;
    }
    if (skepticRiskThreshold == null) {
      skepticRiskThreshold = 0.55d;
    }
    ConfigValidation.minimum("skeptic_risk_threshold", skepticRiskThreshold, 0.0d);
    ConfigValidation.maximum("skeptic_risk_threshold", skepticRiskThreshold, 1.0d);
    if (toolAgentOnDemand == null) {
      toolAgentOnDemand = true;
    }
    if (refereeRequiredBeforeGlobalShare == null) {
      refereeRequiredBeforeGlobalShare = true;
    }
    if (refereeMustDifferFromAuthor == null) {
      refereeMustDifferFromAuthor = true;
    }
    if (allowRoleReuseAfterStage == null) {
      allowRoleReuseAfterStage = true;
    }
    this.enabled = enabled;
    this.maxMembersPerRoute = maxMembersPerRoute;
    this.localReviewRounds = localReviewRounds;
    this.skepticOnHighRiskOnly = skepticOnHighRiskOnly;
    this.skepticRiskThreshold = skepticRiskThreshold;
    this.toolAgentOnDemand = toolAgentOnDemand;
    this.refereeRequiredBeforeGlobalShare = refereeRequiredBeforeGlobalShare;
    this.refereeMustDifferFromAuthor = refereeMustDifferFromAuthor;
    this.allowRoleReuseAfterStage = allowRoleReuseAfterStage;
  }

  public static RouteTeamConfig defaults() {
    return new RouteTeamConfig(null, null, null, null, null, null, null, null, null);
  }
}
