package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactPayload;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerBlockedInference;
import io.github.aililuola.mathproofmesh.contract.BrokerReusableConsequence;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
public record BrokerArtifactCompilationRequest(
    String problemHash,
    String rootGoalHash,
    BrokerArtifactType artifactType,
    BrokerArtifactPayload payload,
    BrokerArtifactSourceKind sourceKind,
    String sourceRouteId,
    String sourceAttemptId,
    String sourceClaimId,
    String sourceClaimRevisionId,
    List<String> sourceObligationIds,
    List<String> sourceProofStepIds,
    List<String> evidenceRefs,
    List<BrokerReusableConsequence> reusableConsequences,
    List<BrokerBlockedInference> blockedInferences,
    List<String> retainedVerifiedClaimIds,
    String nextExactObligationId,
    int roundCreated,
    int ttlRounds,
    boolean sourceAuthorityValid,
    boolean sourceProjectionActive) {
  public BrokerArtifactCompilationRequest {
    problemHash = BrokerArtifactValues.required(problemHash, "problemHash");
    rootGoalHash = BrokerArtifactValues.required(rootGoalHash, "rootGoalHash");
    artifactType = java.util.Objects.requireNonNull(artifactType, "artifactType");
    payload = java.util.Objects.requireNonNull(payload, "payload");
    sourceKind = java.util.Objects.requireNonNull(sourceKind, "sourceKind");
    sourceRouteId = BrokerArtifactValues.required(sourceRouteId, "sourceRouteId");
    sourceAttemptId = BrokerArtifactValues.nullable(sourceAttemptId);
    sourceClaimId = BrokerArtifactValues.nullable(sourceClaimId);
    sourceClaimRevisionId = BrokerArtifactValues.nullable(sourceClaimRevisionId);
    sourceObligationIds = BrokerArtifactValues.list(sourceObligationIds);
    sourceProofStepIds = BrokerArtifactValues.list(sourceProofStepIds);
    evidenceRefs = BrokerArtifactValues.list(evidenceRefs);
    reusableConsequences = BrokerArtifactValues.list(reusableConsequences);
    blockedInferences = BrokerArtifactValues.list(blockedInferences);
    retainedVerifiedClaimIds = BrokerArtifactValues.list(retainedVerifiedClaimIds);
    nextExactObligationId = BrokerArtifactValues.nullable(nextExactObligationId);
    if (roundCreated < 0 || ttlRounds < 1) {
      throw new IllegalArgumentException("artifact round and TTL are invalid");
    }
  }
}
