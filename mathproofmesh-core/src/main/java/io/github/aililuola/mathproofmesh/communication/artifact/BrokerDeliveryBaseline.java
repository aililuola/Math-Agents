package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
public record BrokerDeliveryBaseline(
    String deliveryId,
    String routeId,
    String providerRequestId,
    int consumedRound,
    double canonicalProofDebtBefore,
    Set<String> openCanonicalTargetIdsBefore,
    Set<String> verifiedClaimIdsBefore,
    Set<String> refutedClaimIdsBefore,
    String strategyEpochIdBefore,
    String focusCanonicalTargetIdBefore) {
  public BrokerDeliveryBaseline {
    deliveryId = BrokerArtifactValues.required(deliveryId, "deliveryId");
    routeId = BrokerArtifactValues.required(routeId, "routeId");
    providerRequestId = BrokerArtifactValues.required(providerRequestId, "providerRequestId");
    if (consumedRound < 0 || canonicalProofDebtBefore < 0.0d) {
      throw new IllegalArgumentException("delivery baseline values are invalid");
    }
    openCanonicalTargetIdsBefore = BrokerArtifactValues.set(openCanonicalTargetIdsBefore);
    verifiedClaimIdsBefore = BrokerArtifactValues.set(verifiedClaimIdsBefore);
    refutedClaimIdsBefore = BrokerArtifactValues.set(refutedClaimIdsBefore);
    strategyEpochIdBefore = BrokerArtifactValues.required(strategyEpochIdBefore, "strategyEpochIdBefore");
    focusCanonicalTargetIdBefore = BrokerArtifactValues.nullable(focusCanonicalTargetIdBefore);
  }
}
