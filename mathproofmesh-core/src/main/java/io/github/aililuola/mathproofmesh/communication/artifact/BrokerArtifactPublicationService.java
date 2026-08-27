package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import java.util.Comparator;
import java.util.List;

public final class BrokerArtifactPublicationService {
  private final BrokerArtifactRegistry registry;
  private final BrokerArtifactPublicationLedger publications;
  private final BrokerArtifactTargetingService targeting;
  private final Runnable afterRegistryAdmission;

  public BrokerArtifactPublicationService(
      BrokerArtifactRegistry registry,
      BrokerArtifactPublicationLedger publications,
      BrokerArtifactTargetingService targeting) {
    this(registry, publications, targeting, () -> {});
  }

  BrokerArtifactPublicationService(
      BrokerArtifactRegistry registry,
      BrokerArtifactPublicationLedger publications,
      BrokerArtifactTargetingService targeting,
      Runnable afterRegistryAdmission) {
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
    this.publications = java.util.Objects.requireNonNull(publications, "publications");
    this.targeting = java.util.Objects.requireNonNull(targeting, "targeting");
    this.afterRegistryAdmission =
        java.util.Objects.requireNonNull(afterRegistryAdmission, "afterRegistryAdmission");
  }

  public Publication publish(
      BrokerArtifactEnvelope proposed,
      List<RouteMathematicalNeedProfile> profiles,
      int currentRound,
      int targetLimit) {
    BrokerArtifactEnvelope artifact = registry.admit(proposed);
    afterRegistryAdmission.run();
    List<BrokerArtifactRelevanceDecision> decisions = profiles.stream()
        .map(profile -> targeting.decide(artifact, profile))
        .sorted(Comparator.comparingInt(BrokerArtifactRelevanceDecision::priority).reversed()
            .thenComparing(BrokerArtifactRelevanceDecision::routeId))
        .toList();
    List<String> targets = decisions.stream().filter(BrokerArtifactRelevanceDecision::relevant)
        .limit(Math.max(0, targetLimit)).map(BrokerArtifactRelevanceDecision::routeId).toList();
    BrokerArtifactPublicationRecord record = publications.publish(artifact, targets, currentRound);
    return new Publication(artifact, record, decisions);
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The compact constructor stores immutable defensive copies.")
  public record Publication(
      BrokerArtifactEnvelope artifact,
      BrokerArtifactPublicationRecord publication,
      List<BrokerArtifactRelevanceDecision> relevanceDecisions) {
    public Publication {
      relevanceDecisions = BrokerArtifactValues.list(relevanceDecisions);
    }
  }
}
