package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BrokerPromptArtifact(
    @JsonProperty(value = "artifact_id", required = true) @ContractNonNull String artifactId,
    @JsonProperty(value = "artifact_type", required = true) @ContractNonNull BrokerArtifactType artifactType,
    @JsonProperty(value = "authority", required = true) @ContractNonNull BrokerArtifactAuthority authority,
    @JsonProperty(value = "exact_statement", required = true) @ContractNonNull String exactStatement,
    @JsonProperty("assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty("quantifiers") @ContractNonNull List<QuantifierSpec> quantifiers,
    @JsonProperty("scope") @ContractNonNull List<String> scope,
    @JsonProperty(value = "polarity", required = true) @ContractNonNull String polarity,
    @JsonProperty("source_claim_revision_id") String sourceClaimRevisionId,
    @JsonProperty("evidence_refs") @ContractNonNull List<String> evidenceRefs,
    @JsonProperty("reusable_consequences") @ContractNonNull List<BrokerReusableConsequence> reusableConsequences,
    @JsonProperty("blocked_inferences") @ContractNonNull List<BrokerBlockedInference> blockedInferences,
    @JsonProperty("next_exact_obligation_id") String nextExactObligationId,
    @JsonProperty("allowed_use_kinds") @ContractNonNull List<BrokerArtifactUseKind> allowedUseKinds) {
  public BrokerPromptArtifact {
    artifactId = ContractStrings.required("artifact_id", ContractStrings.trim(artifactId));
    artifactType = ContractValues.required("artifact_type", artifactType);
    authority = ContractValues.required("authority", authority);
    exactStatement = ContractStrings.required("exact_statement", ContractStrings.trim(exactStatement));
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    quantifiers = ImmutableCollections.listOrEmpty(quantifiers);
    scope = ImmutableCollections.listOrEmpty(scope);
    polarity = ContractStrings.required("polarity", ContractStrings.trim(polarity));
    sourceClaimRevisionId = ContractStrings.trim(sourceClaimRevisionId);
    evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    reusableConsequences = ImmutableCollections.listOrEmpty(reusableConsequences);
    blockedInferences = ImmutableCollections.listOrEmpty(blockedInferences);
    nextExactObligationId = ContractStrings.trim(nextExactObligationId);
    allowedUseKinds = ImmutableCollections.listOrEmpty(allowedUseKinds);
  }
  @Override public List<String> assumptions() { return List.copyOf(assumptions); }
  @Override public List<QuantifierSpec> quantifiers() { return List.copyOf(quantifiers); }
  @Override public List<String> scope() { return List.copyOf(scope); }
  @Override public List<String> evidenceRefs() { return List.copyOf(evidenceRefs); }
  @Override public List<BrokerReusableConsequence> reusableConsequences() { return List.copyOf(reusableConsequences); }
  @Override public List<BrokerBlockedInference> blockedInferences() { return List.copyOf(blockedInferences); }
  @Override public List<BrokerArtifactUseKind> allowedUseKinds() { return List.copyOf(allowedUseKinds); }
}
