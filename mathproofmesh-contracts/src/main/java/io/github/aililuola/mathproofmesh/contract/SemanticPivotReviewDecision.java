package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Independent coherence and authority-boundary review of one compiled pivot delta. */
public record SemanticPivotReviewDecision(
    @JsonProperty(value = "pivot_id", required = true) @ContractNonNull String pivotId,
    @JsonProperty(value = "verdict", required = true) @ContractNonNull
        VerificationVerdict verdict,
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "obstruction_binding_valid") @ContractNonNull
        Boolean obstructionBindingValid,
    @JsonProperty(value = "root_goal_preserved") @ContractNonNull Boolean rootGoalPreserved,
    @JsonProperty(value = "object_change_coherent") @ContractNonNull
        Boolean objectChangeCoherent,
    @JsonProperty(value = "target_change_coherent") @ContractNonNull
        Boolean targetChangeCoherent,
    @JsonProperty(value = "retained_claims_compatible") @ContractNonNull
        Boolean retainedClaimsCompatible,
    @JsonProperty(value = "new_obligations_load_bearing") @ContractNonNull
        Boolean newObligationsLoadBearing,
    @JsonProperty(value = "no_authority_escalation") @ContractNonNull
        Boolean noAuthorityEscalation,
    @JsonProperty(value = "issues") @ContractNonNull List<VerificationIssue> issues,
    @JsonProperty(value = "concise_feedback", required = true) @ContractNonNull
        String conciseFeedback)
    implements StrictContract {
  public SemanticPivotReviewDecision {
    pivotId = ContractStrings.required("pivot_id", ContractStrings.trim(pivotId));
    verdict = ContractValues.required("verdict", verdict);
    confidence = ContractValues.required("confidence", confidence);
    ContractValues.minimum("confidence", confidence, 0.0d);
    ContractValues.maximum("confidence", confidence, 1.0d);
    obstructionBindingValid = Boolean.TRUE.equals(obstructionBindingValid);
    rootGoalPreserved = Boolean.TRUE.equals(rootGoalPreserved);
    objectChangeCoherent = Boolean.TRUE.equals(objectChangeCoherent);
    targetChangeCoherent = Boolean.TRUE.equals(targetChangeCoherent);
    retainedClaimsCompatible = Boolean.TRUE.equals(retainedClaimsCompatible);
    newObligationsLoadBearing = Boolean.TRUE.equals(newObligationsLoadBearing);
    noAuthorityEscalation = Boolean.TRUE.equals(noAuthorityEscalation);
    issues = ImmutableCollections.listOrEmpty(issues);
    conciseFeedback =
        ContractStrings.required(
            "concise_feedback", ContractStrings.trim(conciseFeedback));
  }

  public boolean authorityDimensionsValid() {
    return obstructionBindingValid
        && rootGoalPreserved
        && objectChangeCoherent
        && targetChangeCoherent
        && retainedClaimsCompatible
        && newObligationsLoadBearing
        && noAuthorityEscalation;
  }

  @Override
  public List<VerificationIssue> issues() {
    return List.copyOf(issues);
  }
}
