package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BoundedObservationPayload;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactAuthority;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactPayload;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerClaimSemanticContext;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ExactExamplePayload;
import io.github.aililuola.mathproofmesh.contract.FormalCertificatePayload;
import io.github.aililuola.mathproofmesh.contract.ReusableConstructionPayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedClaimPayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedCounterexamplePayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedNoGoPayload;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BrokerArtifactSemanticKey {
  private BrokerArtifactSemanticKey() {}

  public static String of(
      String problemHash,
      String rootGoalHash,
      BrokerArtifactType type,
      BrokerArtifactAuthority authority,
      BrokerArtifactPayload payload,
      String sourceClaimRevisionId) {
    Map<String, Object> key = new LinkedHashMap<>();
    key.put("problem_hash", problemHash);
    key.put("root_goal_hash", rootGoalHash);
    key.put("artifact_type", type.name());
    key.put("authority", authority.name());
    key.put("semantic_context", context(payload));
    key.put("source_claim_revision_id", sourceClaimRevisionId);
    if (payload instanceof VerifiedCounterexamplePayload counterexample) {
      key.put("exact_target_id", counterexample.exactTargetClaimId());
      key.put("witness_hash", CanonicalJson.stableHash(counterexample.witness()));
    } else if (payload instanceof VerifiedNoGoPayload noGo) {
      key.put("exact_target_id", noGo.exactTargetClaimId());
    }
    return CanonicalJson.stableHash(key);
  }

  public static BrokerClaimSemanticContext context(BrokerArtifactPayload payload) {
    if (payload instanceof VerifiedClaimPayload value) return value.claim();
    if (payload instanceof VerifiedCounterexamplePayload value) return value.targetClaim();
    if (payload instanceof VerifiedNoGoPayload value) return value.targetClaim();
    if (payload instanceof ReusableConstructionPayload value) return value.claim();
    if (payload instanceof ExactExamplePayload value) return value.context();
    if (payload instanceof FormalCertificatePayload value) return value.claim();
    if (payload instanceof BoundedObservationPayload value) return value.context();
    return null;
  }
}
