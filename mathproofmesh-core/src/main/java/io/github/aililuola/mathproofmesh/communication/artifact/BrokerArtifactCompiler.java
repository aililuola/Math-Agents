package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactAuthority;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BrokerArtifactCompiler {
  private static final String SCHEMA_VERSION = "1";
  private final BrokerArtifactAuthorityResolver authorityResolver;

  public BrokerArtifactCompiler() {
    this(new BrokerArtifactAuthorityResolver());
  }

  public BrokerArtifactCompiler(BrokerArtifactAuthorityResolver authorityResolver) {
    this.authorityResolver = java.util.Objects.requireNonNull(authorityResolver, "authorityResolver");
  }

  public BrokerArtifactCompilationResult compile(BrokerArtifactCompilationRequest request) {
    BrokerArtifactAuthority authority = authorityResolver.resolve(request).orElse(null);
    if (authority == null) {
      return BrokerArtifactCompilationResult.rejected("UNAUTHORIZED_ARTIFACT_AUTHORITY");
    }
    String semanticHash =
        BrokerArtifactSemanticKey.of(
            request.problemHash(), request.rootGoalHash(), request.artifactType(), authority,
            request.payload(), request.sourceClaimRevisionId());
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("semantic_hash", semanticHash);
    content.put("source_route_id", request.sourceRouteId());
    content.put("source_attempt_id", request.sourceAttemptId());
    content.put("source_claim_id", request.sourceClaimId());
    content.put("source_claim_revision_id", request.sourceClaimRevisionId());
    content.put("source_obligation_ids", request.sourceObligationIds());
    content.put("source_proof_step_ids", request.sourceProofStepIds());
    content.put("evidence_refs", request.evidenceRefs());
    content.put("payload", ContractObjectMapper.toTree(request.payload()));
    String contentHash = CanonicalJson.stableHash(content);
    String artifactId = "broker-artifact-" + contentHash.substring(0, 24);
    return BrokerArtifactCompilationResult.accepted(
        new BrokerArtifactEnvelope(
            artifactId, request.problemHash(), request.rootGoalHash(), request.artifactType(),
            authority, request.payload(), request.sourceRouteId(), request.sourceAttemptId(),
            request.sourceClaimId(), request.sourceClaimRevisionId(), request.sourceObligationIds(),
            request.sourceProofStepIds(), request.evidenceRefs(), request.reusableConsequences(),
            request.blockedInferences(), request.retainedVerifiedClaimIds(),
            request.nextExactObligationId(), request.roundCreated(), request.ttlRounds(),
            semanticHash, contentHash, SCHEMA_VERSION));
  }
}
