package io.github.aililuola.mathproofmesh.communication.artifact;

import java.util.Set;

public record RouteMathematicalNeedProfile(
    String routeId,
    Set<String> activeCanonicalTargetIds,
    Set<String> unresolvedRequiredClaimKeys,
    Set<String> unresolvedDependencyClaimKeys,
    Set<String> focusedBottleneckFamilyIds,
    Set<String> activeObjectRoleIds,
    Set<String> proofIssueKinds,
    String strategyEpochId) {
  public RouteMathematicalNeedProfile {
    routeId = BrokerArtifactValues.required(routeId, "routeId");
    activeCanonicalTargetIds = BrokerArtifactValues.set(activeCanonicalTargetIds);
    unresolvedRequiredClaimKeys = BrokerArtifactValues.set(unresolvedRequiredClaimKeys);
    unresolvedDependencyClaimKeys = BrokerArtifactValues.set(unresolvedDependencyClaimKeys);
    focusedBottleneckFamilyIds = BrokerArtifactValues.set(focusedBottleneckFamilyIds);
    activeObjectRoleIds = BrokerArtifactValues.set(activeObjectRoleIds);
    proofIssueKinds = BrokerArtifactValues.set(proofIssueKinds);
    strategyEpochId = BrokerArtifactValues.required(strategyEpochId, "strategyEpochId");
  }
}
