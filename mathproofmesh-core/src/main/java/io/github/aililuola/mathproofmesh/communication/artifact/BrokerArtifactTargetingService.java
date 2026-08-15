package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerClaimSemanticContext;
import io.github.aililuola.mathproofmesh.contract.ReviewedObstructionPayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedCounterexamplePayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedNoGoPayload;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BrokerArtifactTargetingService {
  public BrokerArtifactRelevanceDecision decide(
      BrokerArtifactEnvelope artifact, RouteMathematicalNeedProfile profile) {
    java.util.Objects.requireNonNull(artifact, "artifact");
    java.util.Objects.requireNonNull(profile, "profile");
    if (artifact.sourceRouteId().equals(profile.routeId())) {
      return new BrokerArtifactRelevanceDecision(
          artifact.artifactId(), profile.routeId(), false, 0, List.of("SOURCE_ROUTE"));
    }
    List<String> reasons = new ArrayList<>();
    BrokerClaimSemanticContext context = BrokerArtifactSemanticKey.context(artifact.payload());
    if (context != null) {
      if (artifact.sourceClaimId() != null
          && profile.unresolvedRequiredClaimKeys().contains(artifact.sourceClaimId())) {
        reasons.add("EXACT_REQUIRED_CLAIM_ID");
      }
      if (artifact.sourceClaimId() != null
          && profile.unresolvedDependencyClaimKeys().contains(artifact.sourceClaimId())) {
        reasons.add("EXACT_DEPENDENCY_CLAIM_ID");
      }
      if (profile.unresolvedRequiredClaimKeys().contains(context.claimSemanticHash())) {
        reasons.add("EXACT_REQUIRED_CLAIM");
      }
      if (profile.unresolvedDependencyClaimKeys().contains(context.claimSemanticHash())) {
        reasons.add("EXACT_DEPENDENCY_CLAIM");
      }
      if (intersects(profile.unresolvedDependencyClaimKeys(), context.dependencyClaimIds())) {
        reasons.add("DEPENDENCY_CLAIM_ID");
      }
    }
    artifact.reusableConsequences().forEach(
        consequence -> {
          if (intersects(profile.activeCanonicalTargetIds(), consequence.canonicalTargetIds())) {
            reasons.add("CANONICAL_TARGET");
          }
          if (intersects(profile.unresolvedDependencyClaimKeys(), consequence.claimSemanticKeys())) {
            reasons.add("CONSEQUENCE_CLAIM_KEY");
          }
          if (intersects(profile.activeObjectRoleIds(), consequence.objectRoleIds())) {
            reasons.add("OBJECT_ROLE");
          }
        });
    artifact.blockedInferences().forEach(
        blocked -> {
          if (intersects(profile.activeCanonicalTargetIds(), blocked.canonicalTargetIds())) {
            reasons.add("BLOCKED_CANONICAL_TARGET");
          }
          if (intersects(profile.unresolvedDependencyClaimKeys(), blocked.claimSemanticKeys())) {
            reasons.add("BLOCKED_DEPENDENCY");
          }
        });
    if (artifact.payload() instanceof VerifiedCounterexamplePayload counterexample
        && (profile.unresolvedRequiredClaimKeys().contains(counterexample.targetSemanticHash())
            || profile.unresolvedDependencyClaimKeys().contains(counterexample.targetSemanticHash())
            || profile.activeCanonicalTargetIds().stream()
                .anyMatch(counterexample.affectedExactObligationIds()::contains))) {
      reasons.add("EXACT_COUNTEREXAMPLE_TARGET");
    }
    if (artifact.payload() instanceof VerifiedNoGoPayload noGo
        && profile.unresolvedDependencyClaimKeys().contains(noGo.targetClaim().claimSemanticHash())) {
      reasons.add("EXACT_NO_GO_TARGET");
    }
    if (artifact.payload() instanceof ReviewedObstructionPayload obstruction
        && profile.proofIssueKinds().contains(obstruction.issueKind())) {
      reasons.add("PROOF_ISSUE_KIND");
    }
    List<String> distinct = List.copyOf(new LinkedHashSet<>(reasons));
    int authorityPriority = switch (artifact.authority()) {
      case REFUTED -> 400;
      case VERIFIED -> 300;
      case REVIEWED_OPEN -> 200;
      case BOUNDED -> 100;
    };
    if (artifact.artifactType() == BrokerArtifactType.VERIFIED_COUNTEREXAMPLE) {
      authorityPriority += 50;
    }
    return new BrokerArtifactRelevanceDecision(
        artifact.artifactId(), profile.routeId(), !distinct.isEmpty(),
        distinct.isEmpty() ? 0 : authorityPriority + distinct.size(), distinct);
  }

  private static boolean intersects(Set<String> left, List<String> right) {
    return right.stream().anyMatch(left::contains);
  }
}
