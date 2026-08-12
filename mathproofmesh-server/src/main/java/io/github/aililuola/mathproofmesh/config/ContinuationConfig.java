package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ContinuationConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "checkpoint_policy") String checkpointPolicy,
    @JsonProperty(value = "max_new_steps_per_call") Integer maxNewStepsPerCall,
    @JsonProperty(value = "max_new_claims_per_call") Integer maxNewClaimsPerCall,
    @JsonProperty(value = "max_output_tokens_per_segment") Integer maxOutputTokensPerSegment,
    @JsonProperty(value = "segments_per_explore_call") Integer segmentsPerExploreCall,
    @JsonProperty(value = "max_segments_per_path") Integer maxSegmentsPerPath,
    @JsonProperty(value = "verify_each_delta") Boolean verifyEachDelta,
    @JsonProperty(value = "delta_verifier_replicas") Integer deltaVerifierReplicas,
    @JsonProperty(value = "allow_checkpoint_rollback") Boolean allowCheckpointRollback,
    @JsonProperty(value = "checkpoint_pass_threshold") Double checkpointPassThreshold,
    @JsonProperty(value = "resume_on_disconnect") Boolean resumeOnDisconnect,
    @JsonProperty(value = "allow_cross_agent_failover") Boolean allowCrossAgentFailover,
    @JsonProperty(value = "max_failover_agents") Integer maxFailoverAgents,
    @JsonProperty(value = "process_resume_enabled") Boolean processResumeEnabled,
    @JsonProperty(value = "retain_rejected_deltas") Boolean retainRejectedDeltas,
    @JsonProperty(value = "post_failure_bottleneck_enabled") Boolean postFailureBottleneckEnabled,
    @JsonProperty(value = "post_failure_bottleneck_max_output_tokens") Integer postFailureBottleneckMaxOutputTokens,
    @JsonProperty(value = "post_failure_bottleneck_once_per_checkpoint") Boolean postFailureBottleneckOncePerCheckpoint,
    @JsonProperty(value = "post_failure_trigger_inspiration") Boolean postFailureTriggerInspiration
) implements ConfigModel {

  @JsonCreator
  public ContinuationConfig(Boolean enabled, String checkpointPolicy, Integer maxNewStepsPerCall, Integer maxNewClaimsPerCall, Integer maxOutputTokensPerSegment, Integer segmentsPerExploreCall, Integer maxSegmentsPerPath, Boolean verifyEachDelta, Integer deltaVerifierReplicas, Boolean allowCheckpointRollback, Double checkpointPassThreshold, Boolean resumeOnDisconnect, Boolean allowCrossAgentFailover, Integer maxFailoverAgents, Boolean processResumeEnabled, Boolean retainRejectedDeltas, Boolean postFailureBottleneckEnabled, Integer postFailureBottleneckMaxOutputTokens, Boolean postFailureBottleneckOncePerCheckpoint, Boolean postFailureTriggerInspiration) {
    if (enabled == null) {
      enabled = false;
    }
    if (checkpointPolicy == null) {
      checkpointPolicy = "verified_subgoal";
    }
    checkpointPolicy = ConfigValidation.trim(checkpointPolicy);
    ConfigValidation.oneOf("checkpoint_policy", checkpointPolicy, "verified_subgoal", "verified_delta");
    if (maxNewStepsPerCall == null) {
      maxNewStepsPerCall = 3;
    }
    ConfigValidation.minimum("max_new_steps_per_call", maxNewStepsPerCall, 1);
    ConfigValidation.maximum("max_new_steps_per_call", maxNewStepsPerCall, 32);
    if (maxNewClaimsPerCall == null) {
      maxNewClaimsPerCall = 3;
    }
    ConfigValidation.minimum("max_new_claims_per_call", maxNewClaimsPerCall, 0);
    ConfigValidation.maximum("max_new_claims_per_call", maxNewClaimsPerCall, 32);
    if (maxOutputTokensPerSegment == null) {
      maxOutputTokensPerSegment = 12000;
    }
    ConfigValidation.minimum("max_output_tokens_per_segment", maxOutputTokensPerSegment, 512);
    ConfigValidation.maximum("max_output_tokens_per_segment", maxOutputTokensPerSegment, 384000);
    if (segmentsPerExploreCall == null) {
      segmentsPerExploreCall = 1;
    }
    ConfigValidation.minimum("segments_per_explore_call", segmentsPerExploreCall, 1);
    ConfigValidation.maximum("segments_per_explore_call", segmentsPerExploreCall, 8);
    if (maxSegmentsPerPath == null) {
      maxSegmentsPerPath = 12;
    }
    ConfigValidation.minimum("max_segments_per_path", maxSegmentsPerPath, 1);
    ConfigValidation.maximum("max_segments_per_path", maxSegmentsPerPath, 128);
    if (verifyEachDelta == null) {
      verifyEachDelta = true;
    }
    if (deltaVerifierReplicas == null) {
      deltaVerifierReplicas = 1;
    }
    ConfigValidation.minimum("delta_verifier_replicas", deltaVerifierReplicas, 1);
    ConfigValidation.maximum("delta_verifier_replicas", deltaVerifierReplicas, 4);
    if (allowCheckpointRollback == null) {
      allowCheckpointRollback = true;
    }
    if (checkpointPassThreshold == null) {
      checkpointPassThreshold = 0.78d;
    }
    ConfigValidation.minimum("checkpoint_pass_threshold", checkpointPassThreshold, 0.0d);
    ConfigValidation.maximum("checkpoint_pass_threshold", checkpointPassThreshold, 1.0d);
    if (resumeOnDisconnect == null) {
      resumeOnDisconnect = true;
    }
    if (allowCrossAgentFailover == null) {
      allowCrossAgentFailover = true;
    }
    if (maxFailoverAgents == null) {
      maxFailoverAgents = 2;
    }
    ConfigValidation.minimum("max_failover_agents", maxFailoverAgents, 0);
    ConfigValidation.maximum("max_failover_agents", maxFailoverAgents, 16);
    if (processResumeEnabled == null) {
      processResumeEnabled = true;
    }
    if (retainRejectedDeltas == null) {
      retainRejectedDeltas = true;
    }
    if (postFailureBottleneckEnabled == null) {
      postFailureBottleneckEnabled = true;
    }
    if (postFailureBottleneckMaxOutputTokens == null) {
      postFailureBottleneckMaxOutputTokens = 16000;
    }
    ConfigValidation.minimum("post_failure_bottleneck_max_output_tokens", postFailureBottleneckMaxOutputTokens, 512);
    ConfigValidation.maximum("post_failure_bottleneck_max_output_tokens", postFailureBottleneckMaxOutputTokens, 32000);
    if (postFailureBottleneckOncePerCheckpoint == null) {
      postFailureBottleneckOncePerCheckpoint = true;
    }
    if (postFailureTriggerInspiration == null) {
      postFailureTriggerInspiration = true;
    }
    this.enabled = enabled;
    this.checkpointPolicy = checkpointPolicy;
    this.maxNewStepsPerCall = maxNewStepsPerCall;
    this.maxNewClaimsPerCall = maxNewClaimsPerCall;
    this.maxOutputTokensPerSegment = maxOutputTokensPerSegment;
    this.segmentsPerExploreCall = segmentsPerExploreCall;
    this.maxSegmentsPerPath = maxSegmentsPerPath;
    this.verifyEachDelta = verifyEachDelta;
    this.deltaVerifierReplicas = deltaVerifierReplicas;
    this.allowCheckpointRollback = allowCheckpointRollback;
    this.checkpointPassThreshold = checkpointPassThreshold;
    this.resumeOnDisconnect = resumeOnDisconnect;
    this.allowCrossAgentFailover = allowCrossAgentFailover;
    this.maxFailoverAgents = maxFailoverAgents;
    this.processResumeEnabled = processResumeEnabled;
    this.retainRejectedDeltas = retainRejectedDeltas;
    this.postFailureBottleneckEnabled = postFailureBottleneckEnabled;
    this.postFailureBottleneckMaxOutputTokens = postFailureBottleneckMaxOutputTokens;
    this.postFailureBottleneckOncePerCheckpoint = postFailureBottleneckOncePerCheckpoint;
    this.postFailureTriggerInspiration = postFailureTriggerInspiration;
  }

  public static ContinuationConfig defaults() {
    return new ContinuationConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
