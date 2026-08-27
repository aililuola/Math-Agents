package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactAuthority;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public final class BrokerArtifactAuthorityResolver {
  private static final Map<BrokerArtifactType, BrokerArtifactAuthority> MATRIX = matrix();

  public Optional<BrokerArtifactAuthority> resolve(BrokerArtifactCompilationRequest request) {
    java.util.Objects.requireNonNull(request, "request");
    if (!request.sourceAuthorityValid() || !request.sourceProjectionActive()) {
      return Optional.empty();
    }
    if (request.artifactType() == BrokerArtifactType.EXACT_EXAMPLE
        && request.sourceKind() == BrokerArtifactSourceKind.BOUNDED_EVIDENCE) {
      return Optional.of(BrokerArtifactAuthority.BOUNDED);
    }
    boolean sourceAllowed = switch (request.artifactType()) {
      case VERIFIED_CLAIM -> request.sourceKind() == BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED;
      case VERIFIED_COUNTEREXAMPLE -> request.sourceKind() == BrokerArtifactSourceKind.VERIFIED_COUNTEREXAMPLE;
      case VERIFIED_NO_GO -> request.sourceKind() == BrokerArtifactSourceKind.REFUTED_STATEMENT;
      case REVIEWED_OBSTRUCTION -> request.sourceKind() == BrokerArtifactSourceKind.REVIEWED_PROOF_OBSTRUCTION;
      case REUSABLE_CONSTRUCTION -> request.sourceKind() == BrokerArtifactSourceKind.VERIFIED_CONSTRUCTION;
      case EXACT_EXAMPLE -> request.sourceKind() == BrokerArtifactSourceKind.AUDITED_EXACT_EXAMPLE;
      case FORMAL_CERTIFICATE -> request.sourceKind() == BrokerArtifactSourceKind.TRUSTED_FORMAL_CERTIFICATE;
      case BOUNDED_OBSERVATION -> request.sourceKind() == BrokerArtifactSourceKind.BOUNDED_EVIDENCE;
    };
    return sourceAllowed ? Optional.of(MATRIX.get(request.artifactType())) : Optional.empty();
  }

  public boolean compatible(BrokerArtifactType type, BrokerArtifactAuthority authority) {
    return MATRIX.get(type) == authority
        || (type == BrokerArtifactType.EXACT_EXAMPLE
            && authority == BrokerArtifactAuthority.BOUNDED);
  }

  private static Map<BrokerArtifactType, BrokerArtifactAuthority> matrix() {
    EnumMap<BrokerArtifactType, BrokerArtifactAuthority> matrix =
        new EnumMap<>(BrokerArtifactType.class);
    matrix.put(BrokerArtifactType.VERIFIED_CLAIM, BrokerArtifactAuthority.VERIFIED);
    matrix.put(BrokerArtifactType.VERIFIED_COUNTEREXAMPLE, BrokerArtifactAuthority.REFUTED);
    matrix.put(BrokerArtifactType.VERIFIED_NO_GO, BrokerArtifactAuthority.REFUTED);
    matrix.put(BrokerArtifactType.REVIEWED_OBSTRUCTION, BrokerArtifactAuthority.REVIEWED_OPEN);
    matrix.put(BrokerArtifactType.REUSABLE_CONSTRUCTION, BrokerArtifactAuthority.VERIFIED);
    matrix.put(BrokerArtifactType.EXACT_EXAMPLE, BrokerArtifactAuthority.VERIFIED);
    matrix.put(BrokerArtifactType.FORMAL_CERTIFICATE, BrokerArtifactAuthority.VERIFIED);
    matrix.put(BrokerArtifactType.BOUNDED_OBSERVATION, BrokerArtifactAuthority.BOUNDED);
    return Map.copyOf(matrix);
  }
}
