package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CommonModeControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "min_routes") Integer minRoutes,
    @JsonProperty(value = "risk_threshold") Double riskThreshold,
    @JsonProperty(value = "max_challengers_per_round") Integer maxChallengersPerRound,
    @JsonProperty(value = "include_strategy_prerequisites") Boolean includeStrategyPrerequisites,
    @JsonProperty(value = "include_critical_claims") Boolean includeCriticalClaims,
    @JsonProperty(value = "include_unverified_dependencies") Boolean includeUnverifiedDependencies
) implements ConfigModel {

  @JsonCreator
  public CommonModeControlConfig(Boolean enabled, Integer minRoutes, Double riskThreshold, Integer maxChallengersPerRound, Boolean includeStrategyPrerequisites, Boolean includeCriticalClaims, Boolean includeUnverifiedDependencies) {
    if (enabled == null) {
      enabled = true;
    }
    if (minRoutes == null) {
      minRoutes = 3;
    }
    ConfigValidation.minimum("min_routes", minRoutes, 2);
    ConfigValidation.maximum("min_routes", minRoutes, 32);
    if (riskThreshold == null) {
      riskThreshold = 0.6d;
    }
    ConfigValidation.minimum("risk_threshold", riskThreshold, 0.0d);
    ConfigValidation.maximum("risk_threshold", riskThreshold, 1.0d);
    if (maxChallengersPerRound == null) {
      maxChallengersPerRound = 1;
    }
    ConfigValidation.minimum("max_challengers_per_round", maxChallengersPerRound, 0);
    ConfigValidation.maximum("max_challengers_per_round", maxChallengersPerRound, 8);
    if (includeStrategyPrerequisites == null) {
      includeStrategyPrerequisites = true;
    }
    if (includeCriticalClaims == null) {
      includeCriticalClaims = true;
    }
    if (includeUnverifiedDependencies == null) {
      includeUnverifiedDependencies = true;
    }
    this.enabled = enabled;
    this.minRoutes = minRoutes;
    this.riskThreshold = riskThreshold;
    this.maxChallengersPerRound = maxChallengersPerRound;
    this.includeStrategyPrerequisites = includeStrategyPrerequisites;
    this.includeCriticalClaims = includeCriticalClaims;
    this.includeUnverifiedDependencies = includeUnverifiedDependencies;
  }

  public static CommonModeControlConfig defaults() {
    return new CommonModeControlConfig(null, null, null, null, null, null, null);
  }
}
