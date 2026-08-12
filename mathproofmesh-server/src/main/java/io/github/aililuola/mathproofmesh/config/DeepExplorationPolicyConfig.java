package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record DeepExplorationPolicyConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "tiers") List<ExplorationTierPolicyConfig> tiers,
    @JsonProperty(value = "high_tier_threshold_tokens") Integer highTierThresholdTokens,
    @JsonProperty(value = "partial_repair_max_output_tokens") Integer partialRepairMaxOutputTokens,
    @JsonProperty(value = "max_partial_repairs_per_signature") Integer maxPartialRepairsPerSignature,
    @JsonProperty(value = "max_running_per_signature") Integer maxRunningPerSignature,
    @JsonProperty(value = "no_progress_high_tier_limit_per_signature") Integer noProgressHighTierLimitPerSignature,
    @JsonProperty(value = "semantic_duplicate_threshold") Double semanticDuplicateThreshold,
    @JsonProperty(value = "allow_parallel_distinct_signatures") Boolean allowParallelDistinctSignatures,
    @JsonProperty(value = "allow_local_bottleneck_pivot") Boolean allowLocalBottleneckPivot,
    @JsonProperty(value = "require_novelty_review_for_pivot") Boolean requireNoveltyReviewForPivot,
    @JsonProperty(value = "require_meta_approval_for_128k") Boolean requireMetaApprovalFor128k,
    @JsonProperty(value = "min_remaining_calls_for_128k") Integer minRemainingCallsFor128k,
    @JsonProperty(value = "min_remaining_tokens_for_128k") Integer minRemainingTokensFor128k,
    @JsonProperty(value = "preserve_parent_verified_checkpoint") Boolean preserveParentVerifiedCheckpoint,
    @JsonProperty(value = "reset_on_verified_checkpoint") Boolean resetOnVerifiedCheckpoint,
    @JsonProperty(value = "reset_on_referee_confirmed_mechanism_change") Boolean resetOnRefereeConfirmedMechanismChange,
    @JsonProperty(value = "persist_across_resume") Boolean persistAcrossResume
) implements ConfigModel {

  @JsonCreator
  public DeepExplorationPolicyConfig(Boolean enabled, List<ExplorationTierPolicyConfig> tiers, Integer highTierThresholdTokens, Integer partialRepairMaxOutputTokens, Integer maxPartialRepairsPerSignature, Integer maxRunningPerSignature, Integer noProgressHighTierLimitPerSignature, Double semanticDuplicateThreshold, Boolean allowParallelDistinctSignatures, Boolean allowLocalBottleneckPivot, Boolean requireNoveltyReviewForPivot, Boolean requireMetaApprovalFor128k, Integer minRemainingCallsFor128k, Integer minRemainingTokensFor128k, Boolean preserveParentVerifiedCheckpoint, Boolean resetOnVerifiedCheckpoint, Boolean resetOnRefereeConfirmedMechanismChange, Boolean persistAcrossResume) {
    if (enabled == null) {
      enabled = true;
    }
    if (tiers == null) {
      tiers = List.of(new ExplorationTierPolicyConfig(64000, 8000), new ExplorationTierPolicyConfig(96000, 12000), new ExplorationTierPolicyConfig(128000, 16000));
    }
    tiers = ConfigValidation.immutableList("tiers", tiers);
    ConfigValidation.minimumLength("tiers", tiers, 1);
    ConfigValidation.maximumLength("tiers", tiers, 8);
    if (highTierThresholdTokens == null) {
      highTierThresholdTokens = 96000;
    }
    ConfigValidation.minimum("high_tier_threshold_tokens", highTierThresholdTokens, 512);
    ConfigValidation.maximum("high_tier_threshold_tokens", highTierThresholdTokens, 384000);
    if (partialRepairMaxOutputTokens == null) {
      partialRepairMaxOutputTokens = 64000;
    }
    ConfigValidation.minimum("partial_repair_max_output_tokens", partialRepairMaxOutputTokens, 512);
    ConfigValidation.maximum("partial_repair_max_output_tokens", partialRepairMaxOutputTokens, 384000);
    if (maxPartialRepairsPerSignature == null) {
      maxPartialRepairsPerSignature = 1;
    }
    ConfigValidation.minimum("max_partial_repairs_per_signature", maxPartialRepairsPerSignature, 0);
    ConfigValidation.maximum("max_partial_repairs_per_signature", maxPartialRepairsPerSignature, 4);
    if (maxRunningPerSignature == null) {
      maxRunningPerSignature = 1;
    }
    ConfigValidation.minimum("max_running_per_signature", maxRunningPerSignature, 1);
    ConfigValidation.maximum("max_running_per_signature", maxRunningPerSignature, 4);
    if (noProgressHighTierLimitPerSignature == null) {
      noProgressHighTierLimitPerSignature = 1;
    }
    ConfigValidation.minimum("no_progress_high_tier_limit_per_signature", noProgressHighTierLimitPerSignature, 1);
    ConfigValidation.maximum("no_progress_high_tier_limit_per_signature", noProgressHighTierLimitPerSignature, 8);
    if (semanticDuplicateThreshold == null) {
      semanticDuplicateThreshold = 0.86d;
    }
    ConfigValidation.minimum("semantic_duplicate_threshold", semanticDuplicateThreshold, 0.5d);
    ConfigValidation.maximum("semantic_duplicate_threshold", semanticDuplicateThreshold, 1.0d);
    if (allowParallelDistinctSignatures == null) {
      allowParallelDistinctSignatures = true;
    }
    if (allowLocalBottleneckPivot == null) {
      allowLocalBottleneckPivot = true;
    }
    if (requireNoveltyReviewForPivot == null) {
      requireNoveltyReviewForPivot = true;
    }
    if (requireMetaApprovalFor128k == null) {
      requireMetaApprovalFor128k = true;
    }
    if (minRemainingCallsFor128k == null) {
      minRemainingCallsFor128k = 8;
    }
    ConfigValidation.minimum("min_remaining_calls_for_128k", minRemainingCallsFor128k, 1);
    ConfigValidation.maximum("min_remaining_calls_for_128k", minRemainingCallsFor128k, 1000);
    if (minRemainingTokensFor128k == null) {
      minRemainingTokensFor128k = 256000;
    }
    ConfigValidation.minimum("min_remaining_tokens_for_128k", minRemainingTokensFor128k, 128000);
    ConfigValidation.maximum("min_remaining_tokens_for_128k", minRemainingTokensFor128k, 1000000000);
    if (preserveParentVerifiedCheckpoint == null) {
      preserveParentVerifiedCheckpoint = true;
    }
    if (resetOnVerifiedCheckpoint == null) {
      resetOnVerifiedCheckpoint = true;
    }
    if (resetOnRefereeConfirmedMechanismChange == null) {
      resetOnRefereeConfirmedMechanismChange = true;
    }
    if (persistAcrossResume == null) {
      persistAcrossResume = true;
    }
    this.enabled = enabled;
    this.tiers = tiers;
    this.highTierThresholdTokens = highTierThresholdTokens;
    this.partialRepairMaxOutputTokens = partialRepairMaxOutputTokens;
    this.maxPartialRepairsPerSignature = maxPartialRepairsPerSignature;
    this.maxRunningPerSignature = maxRunningPerSignature;
    this.noProgressHighTierLimitPerSignature = noProgressHighTierLimitPerSignature;
    this.semanticDuplicateThreshold = semanticDuplicateThreshold;
    this.allowParallelDistinctSignatures = allowParallelDistinctSignatures;
    this.allowLocalBottleneckPivot = allowLocalBottleneckPivot;
    this.requireNoveltyReviewForPivot = requireNoveltyReviewForPivot;
    this.requireMetaApprovalFor128k = requireMetaApprovalFor128k;
    this.minRemainingCallsFor128k = minRemainingCallsFor128k;
    this.minRemainingTokensFor128k = minRemainingTokensFor128k;
    this.preserveParentVerifiedCheckpoint = preserveParentVerifiedCheckpoint;
    this.resetOnVerifiedCheckpoint = resetOnVerifiedCheckpoint;
    this.resetOnRefereeConfirmedMechanismChange = resetOnRefereeConfirmedMechanismChange;
    this.persistAcrossResume = persistAcrossResume;
    ConfigInvariants.validate(this);
  }

  public static DeepExplorationPolicyConfig defaults() {
    return new DeepExplorationPolicyConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  @JsonProperty("tiers")
  @Override
  public List<ExplorationTierPolicyConfig> tiers() {
    return tiers == null ? null : List.copyOf(tiers);
  }


  public ExplorationTierPolicyConfig tierForLimit(Integer outputTokenLimit) {
    int requested = outputTokenLimit == null ? tiers.getFirst().outputTokens() : outputTokenLimit;
    ExplorationTierPolicyConfig selected = tiers.getFirst();
    for (ExplorationTierPolicyConfig tier : tiers) {
      if (tier.outputTokens() <= requested) {
        selected = tier;
      }
    }
    return selected;
  }

  public int tierIndexForLimit(Integer outputTokenLimit) {
    return tiers.indexOf(tierForLimit(outputTokenLimit));
  }

}
