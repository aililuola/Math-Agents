package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BrokerArtifactEnvelope(
    @JsonProperty(value = "artifact_id", required = true) @ContractNonNull String artifactId,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "root_goal_hash", required = true) @ContractNonNull String rootGoalHash,
    @JsonProperty(value = "artifact_type", required = true) @ContractNonNull
        BrokerArtifactType artifactType,
    @JsonProperty(value = "authority", required = true) @ContractNonNull
        BrokerArtifactAuthority authority,
    @JsonProperty(value = "payload", required = true) @ContractNonNull BrokerArtifactPayload payload,
    @JsonProperty(value = "source_route_id", required = true) @ContractNonNull String sourceRouteId,
    @JsonProperty("source_attempt_id") String sourceAttemptId,
    @JsonProperty("source_claim_id") String sourceClaimId,
    @JsonProperty("source_claim_revision_id") String sourceClaimRevisionId,
    @JsonProperty("source_obligation_ids") @ContractNonNull List<String> sourceObligationIds,
    @JsonProperty("source_proof_step_ids") @ContractNonNull List<String> sourceProofStepIds,
    @JsonProperty("evidence_refs") @ContractNonNull List<String> evidenceRefs,
    @JsonProperty("reusable_consequences") @ContractNonNull
        List<BrokerReusableConsequence> reusableConsequences,
    @JsonProperty("blocked_inferences") @ContractNonNull
        List<BrokerBlockedInference> blockedInferences,
    @JsonProperty("retained_verified_claim_ids") @ContractNonNull
        List<String> retainedVerifiedClaimIds,
    @JsonProperty("next_exact_obligation_id") String nextExactObligationId,
    @JsonProperty(value = "round_created", required = true) int roundCreated,
    @JsonProperty(value = "ttl_rounds", required = true) int ttlRounds,
    @JsonProperty(value = "semantic_hash", required = true) @ContractNonNull String semanticHash,
    @JsonProperty(value = "content_hash", required = true) @ContractNonNull String contentHash,
    @JsonProperty(value = "schema_version", required = true) @ContractNonNull String schemaVersion) {

  public BrokerArtifactEnvelope {
    artifactId = ContractStrings.required("artifact_id", ContractStrings.trim(artifactId));
    problemHash = ContractStrings.required("problem_hash", ContractStrings.trim(problemHash));
    rootGoalHash = ContractStrings.required("root_goal_hash", ContractStrings.trim(rootGoalHash));
    artifactType = ContractValues.required("artifact_type", artifactType);
    authority = ContractValues.required("authority", authority);
    payload = ContractValues.required("payload", payload);
    sourceRouteId = ContractStrings.required("source_route_id", ContractStrings.trim(sourceRouteId));
    sourceAttemptId = ContractStrings.trim(sourceAttemptId);
    sourceClaimId = ContractStrings.trim(sourceClaimId);
    sourceClaimRevisionId = ContractStrings.trim(sourceClaimRevisionId);
    sourceObligationIds = ImmutableCollections.listOrEmpty(sourceObligationIds);
    sourceProofStepIds = ImmutableCollections.listOrEmpty(sourceProofStepIds);
    evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    reusableConsequences = ImmutableCollections.listOrEmpty(reusableConsequences);
    blockedInferences = ImmutableCollections.listOrEmpty(blockedInferences);
    retainedVerifiedClaimIds = ImmutableCollections.listOrEmpty(retainedVerifiedClaimIds);
    nextExactObligationId = ContractStrings.trim(nextExactObligationId);
    ContractValues.minimum("round_created", roundCreated, 0);
    ContractValues.minimum("ttl_rounds", ttlRounds, 1);
    semanticHash = ContractStrings.required("semantic_hash", ContractStrings.trim(semanticHash));
    contentHash = ContractStrings.required("content_hash", ContractStrings.trim(contentHash));
    schemaVersion = ContractStrings.required("schema_version", ContractStrings.trim(schemaVersion));
  }

  @Override public List<String> sourceObligationIds() { return List.copyOf(sourceObligationIds); }
  @Override public List<String> sourceProofStepIds() { return List.copyOf(sourceProofStepIds); }
  @Override public List<String> evidenceRefs() { return List.copyOf(evidenceRefs); }
  @Override public List<BrokerReusableConsequence> reusableConsequences() { return List.copyOf(reusableConsequences); }
  @Override public List<BrokerBlockedInference> blockedInferences() { return List.copyOf(blockedInferences); }
  @Override public List<String> retainedVerifiedClaimIds() { return List.copyOf(retainedVerifiedClaimIds); }
}
