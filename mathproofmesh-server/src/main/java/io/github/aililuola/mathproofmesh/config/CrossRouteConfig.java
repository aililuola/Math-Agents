package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CrossRouteConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "initial_isolation_rounds") Integer initialIsolationRounds,
    @JsonProperty(value = "max_neighbors_per_route") Integer maxNeighborsPerRoute,
    @JsonProperty(value = "max_messages_per_route_per_round") Integer maxMessagesPerRoutePerRound,
    @JsonProperty(value = "max_global_messages_per_round") Integer maxGlobalMessagesPerRound,
    @JsonProperty(value = "share_verified_facts") Boolean shareVerifiedFacts,
    @JsonProperty(value = "share_counterexamples") Boolean shareCounterexamples,
    @JsonProperty(value = "share_open_obligations") Boolean shareOpenObligations,
    @JsonProperty(value = "share_unverified_insights") Boolean shareUnverifiedInsights,
    @JsonProperty(value = "share_failure_records") Boolean shareFailureRecords,
    @JsonProperty(value = "message_ttl_rounds") Integer messageTtlRounds
) implements ConfigModel {

  @JsonCreator
  public CrossRouteConfig(Boolean enabled, Integer initialIsolationRounds, Integer maxNeighborsPerRoute, Integer maxMessagesPerRoutePerRound, Integer maxGlobalMessagesPerRound, Boolean shareVerifiedFacts, Boolean shareCounterexamples, Boolean shareOpenObligations, Boolean shareUnverifiedInsights, Boolean shareFailureRecords, Integer messageTtlRounds) {
    if (enabled == null) {
      enabled = false;
    }
    if (initialIsolationRounds == null) {
      initialIsolationRounds = 1;
    }
    ConfigValidation.minimum("initial_isolation_rounds", initialIsolationRounds, 0);
    ConfigValidation.maximum("initial_isolation_rounds", initialIsolationRounds, 16);
    if (maxNeighborsPerRoute == null) {
      maxNeighborsPerRoute = 2;
    }
    ConfigValidation.minimum("max_neighbors_per_route", maxNeighborsPerRoute, 0);
    ConfigValidation.maximum("max_neighbors_per_route", maxNeighborsPerRoute, 32);
    if (maxMessagesPerRoutePerRound == null) {
      maxMessagesPerRoutePerRound = 6;
    }
    ConfigValidation.minimum("max_messages_per_route_per_round", maxMessagesPerRoutePerRound, 0);
    ConfigValidation.maximum("max_messages_per_route_per_round", maxMessagesPerRoutePerRound, 128);
    if (maxGlobalMessagesPerRound == null) {
      maxGlobalMessagesPerRound = 24;
    }
    ConfigValidation.minimum("max_global_messages_per_round", maxGlobalMessagesPerRound, 1);
    ConfigValidation.maximum("max_global_messages_per_round", maxGlobalMessagesPerRound, 1024);
    if (shareVerifiedFacts == null) {
      shareVerifiedFacts = true;
    }
    if (shareCounterexamples == null) {
      shareCounterexamples = true;
    }
    if (shareOpenObligations == null) {
      shareOpenObligations = true;
    }
    if (shareUnverifiedInsights == null) {
      shareUnverifiedInsights = false;
    }
    if (shareFailureRecords == null) {
      shareFailureRecords = true;
    }
    if (messageTtlRounds == null) {
      messageTtlRounds = 2;
    }
    ConfigValidation.minimum("message_ttl_rounds", messageTtlRounds, 1);
    ConfigValidation.maximum("message_ttl_rounds", messageTtlRounds, 32);
    this.enabled = enabled;
    this.initialIsolationRounds = initialIsolationRounds;
    this.maxNeighborsPerRoute = maxNeighborsPerRoute;
    this.maxMessagesPerRoutePerRound = maxMessagesPerRoutePerRound;
    this.maxGlobalMessagesPerRound = maxGlobalMessagesPerRound;
    this.shareVerifiedFacts = shareVerifiedFacts;
    this.shareCounterexamples = shareCounterexamples;
    this.shareOpenObligations = shareOpenObligations;
    this.shareUnverifiedInsights = shareUnverifiedInsights;
    this.shareFailureRecords = shareFailureRecords;
    this.messageTtlRounds = messageTtlRounds;
  }

  public static CrossRouteConfig defaults() {
    return new CrossRouteConfig(null, null, null, null, null, null, null, null, null, null, null);
  }
}
