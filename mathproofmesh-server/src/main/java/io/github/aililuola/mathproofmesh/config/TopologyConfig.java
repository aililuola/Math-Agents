package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record TopologyConfig(
    @JsonProperty(value = "neighbor_k") Integer neighborK,
    @JsonProperty(value = "prefer_cross_provider_review") Boolean preferCrossProviderReview,
    @JsonProperty(value = "isolate_initial_exploration") Boolean isolateInitialExploration,
    @JsonProperty(value = "conditional_cross_review") Boolean conditionalCrossReview,
    @JsonProperty(value = "disagreement_threshold") Double disagreementThreshold,
    @JsonProperty(value = "strategy_similarity_threshold") Double strategySimilarityThreshold,
    @JsonProperty(value = "max_context_chars") Integer maxContextChars,
    @JsonProperty(value = "max_verified_claims_per_context") Integer maxVerifiedClaimsPerContext,
    @JsonProperty(value = "preserve_raw_evidence") Boolean preserveRawEvidence,
    @JsonProperty(value = "mode") String mode,
    @JsonProperty(value = "typed_communication") TypedCommunicationConfig typedCommunication,
    @JsonProperty(value = "route_teams") RouteTeamConfig routeTeams,
    @JsonProperty(value = "cross_route") CrossRouteConfig crossRoute,
    @JsonProperty(value = "broker") BrokerConfig broker,
    @JsonProperty(value = "proof_graph") ProofGraphConfig proofGraph,
    @JsonProperty(value = "typed_memory") TypedMemoryConfig typedMemory,
    @JsonProperty(value = "final_stage") FinalTopologyConfig finalStage,
    @JsonProperty(value = "inspiration") InspirationConfig inspiration,
    @JsonProperty(value = "validation_escalation") ValidationEscalationConfig validationEscalation,
    @JsonProperty(value = "agent_capability") AgentCapabilityConfig agentCapability,
    @JsonProperty(value = "proof_control") ProofControlConfig proofControl
) implements ConfigModel {

  @JsonCreator
  public TopologyConfig(Integer neighborK, Boolean preferCrossProviderReview, Boolean isolateInitialExploration, Boolean conditionalCrossReview, Double disagreementThreshold, Double strategySimilarityThreshold, Integer maxContextChars, Integer maxVerifiedClaimsPerContext, Boolean preserveRawEvidence, String mode, TypedCommunicationConfig typedCommunication, RouteTeamConfig routeTeams, CrossRouteConfig crossRoute, BrokerConfig broker, ProofGraphConfig proofGraph, TypedMemoryConfig typedMemory, FinalTopologyConfig finalStage, InspirationConfig inspiration, ValidationEscalationConfig validationEscalation, AgentCapabilityConfig agentCapability, ProofControlConfig proofControl) {
    if (neighborK == null) {
      neighborK = 2;
    }
    ConfigValidation.minimum("neighbor_k", neighborK, 1);
    ConfigValidation.maximum("neighbor_k", neighborK, 16);
    if (preferCrossProviderReview == null) {
      preferCrossProviderReview = true;
    }
    if (isolateInitialExploration == null) {
      isolateInitialExploration = true;
    }
    if (conditionalCrossReview == null) {
      conditionalCrossReview = true;
    }
    if (disagreementThreshold == null) {
      disagreementThreshold = 0.35d;
    }
    ConfigValidation.minimum("disagreement_threshold", disagreementThreshold, 0.0d);
    ConfigValidation.maximum("disagreement_threshold", disagreementThreshold, 1.0d);
    if (strategySimilarityThreshold == null) {
      strategySimilarityThreshold = 0.72d;
    }
    ConfigValidation.minimum("strategy_similarity_threshold", strategySimilarityThreshold, 0.0d);
    ConfigValidation.maximum("strategy_similarity_threshold", strategySimilarityThreshold, 1.0d);
    if (maxContextChars == null) {
      maxContextChars = 90000;
    }
    ConfigValidation.minimum("max_context_chars", maxContextChars, 4000);
    ConfigValidation.maximum("max_context_chars", maxContextChars, 2000000);
    if (maxVerifiedClaimsPerContext == null) {
      maxVerifiedClaimsPerContext = 24;
    }
    ConfigValidation.minimum("max_verified_claims_per_context", maxVerifiedClaimsPerContext, 1);
    ConfigValidation.maximum("max_verified_claims_per_context", maxVerifiedClaimsPerContext, 500);
    if (preserveRawEvidence == null) {
      preserveRawEvidence = true;
    }
    if (mode == null) {
      mode = "legacy_sparse";
    }
    mode = ConfigValidation.trim(mode);
    ConfigValidation.oneOf("mode", mode, "legacy_sparse", "hierarchical_sparse");
    if (typedCommunication == null) {
      typedCommunication = TypedCommunicationConfig.defaults();
    }
    if (routeTeams == null) {
      routeTeams = RouteTeamConfig.defaults();
    }
    if (crossRoute == null) {
      crossRoute = CrossRouteConfig.defaults();
    }
    if (broker == null) {
      broker = BrokerConfig.defaults();
    }
    if (proofGraph == null) {
      proofGraph = ProofGraphConfig.defaults();
    }
    if (typedMemory == null) {
      typedMemory = TypedMemoryConfig.defaults();
    }
    if (finalStage == null) {
      finalStage = FinalTopologyConfig.defaults();
    }
    if (inspiration == null) {
      inspiration = InspirationConfig.defaults();
    }
    if (validationEscalation == null) {
      validationEscalation = ValidationEscalationConfig.defaults();
    }
    if (agentCapability == null) {
      agentCapability = AgentCapabilityConfig.defaults();
    }
    if (proofControl == null) {
      proofControl = ProofControlConfig.defaults();
    }
    this.neighborK = neighborK;
    this.preferCrossProviderReview = preferCrossProviderReview;
    this.isolateInitialExploration = isolateInitialExploration;
    this.conditionalCrossReview = conditionalCrossReview;
    this.disagreementThreshold = disagreementThreshold;
    this.strategySimilarityThreshold = strategySimilarityThreshold;
    this.maxContextChars = maxContextChars;
    this.maxVerifiedClaimsPerContext = maxVerifiedClaimsPerContext;
    this.preserveRawEvidence = preserveRawEvidence;
    this.mode = mode;
    this.typedCommunication = typedCommunication;
    this.routeTeams = routeTeams;
    this.crossRoute = crossRoute;
    this.broker = broker;
    this.proofGraph = proofGraph;
    this.typedMemory = typedMemory;
    this.finalStage = finalStage;
    this.inspiration = inspiration;
    this.validationEscalation = validationEscalation;
    this.agentCapability = agentCapability;
    this.proofControl = proofControl;
    ConfigInvariants.validate(this);
  }

  public static TopologyConfig defaults() {
    return new TopologyConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
