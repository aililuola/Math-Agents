package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ValidationEscalationConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "deterministic_checks_first") Boolean deterministicChecksFirst,
    @JsonProperty(value = "blind_same_model_review") Boolean blindSameModelReview,
    @JsonProperty(value = "adversarial_prompt_review") Boolean adversarialPromptReview,
    @JsonProperty(value = "cross_provider_review") Boolean crossProviderReview,
    @JsonProperty(value = "tool_or_formal_check_on_high_risk") Boolean toolOrFormalCheckOnHighRisk,
    @JsonProperty(value = "high_risk_threshold") Double highRiskThreshold,
    @JsonProperty(value = "escalate_on_reviewer_disagreement") Boolean escalateOnReviewerDisagreement,
    @JsonProperty(value = "escalate_before_fact_promotion") Boolean escalateBeforeFactPromotion,
    @JsonProperty(value = "escalate_final_proof") Boolean escalateFinalProof
) implements ConfigModel {

  @JsonCreator
  public ValidationEscalationConfig(Boolean enabled, Boolean deterministicChecksFirst, Boolean blindSameModelReview, Boolean adversarialPromptReview, Boolean crossProviderReview, Boolean toolOrFormalCheckOnHighRisk, Double highRiskThreshold, Boolean escalateOnReviewerDisagreement, Boolean escalateBeforeFactPromotion, Boolean escalateFinalProof) {
    if (enabled == null) {
      enabled = true;
    }
    if (deterministicChecksFirst == null) {
      deterministicChecksFirst = true;
    }
    if (blindSameModelReview == null) {
      blindSameModelReview = true;
    }
    if (adversarialPromptReview == null) {
      adversarialPromptReview = true;
    }
    if (crossProviderReview == null) {
      crossProviderReview = false;
    }
    if (toolOrFormalCheckOnHighRisk == null) {
      toolOrFormalCheckOnHighRisk = true;
    }
    if (highRiskThreshold == null) {
      highRiskThreshold = 0.75d;
    }
    ConfigValidation.minimum("high_risk_threshold", highRiskThreshold, 0.0d);
    ConfigValidation.maximum("high_risk_threshold", highRiskThreshold, 1.0d);
    if (escalateOnReviewerDisagreement == null) {
      escalateOnReviewerDisagreement = true;
    }
    if (escalateBeforeFactPromotion == null) {
      escalateBeforeFactPromotion = true;
    }
    if (escalateFinalProof == null) {
      escalateFinalProof = true;
    }
    this.enabled = enabled;
    this.deterministicChecksFirst = deterministicChecksFirst;
    this.blindSameModelReview = blindSameModelReview;
    this.adversarialPromptReview = adversarialPromptReview;
    this.crossProviderReview = crossProviderReview;
    this.toolOrFormalCheckOnHighRisk = toolOrFormalCheckOnHighRisk;
    this.highRiskThreshold = highRiskThreshold;
    this.escalateOnReviewerDisagreement = escalateOnReviewerDisagreement;
    this.escalateBeforeFactPromotion = escalateBeforeFactPromotion;
    this.escalateFinalProof = escalateFinalProof;
  }

  public static ValidationEscalationConfig defaults() {
    return new ValidationEscalationConfig(null, null, null, null, null, null, null, null, null, null);
  }
}
