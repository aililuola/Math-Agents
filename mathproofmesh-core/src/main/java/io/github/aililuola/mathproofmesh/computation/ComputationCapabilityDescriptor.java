package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import java.util.Objects;

/** Immutable server-owned description of one typed computation capability. */
public record ComputationCapabilityDescriptor(
    ComputationMethod method,
    String capabilityId,
    String capabilityVersion,
    ComputationBackendKind backendKind,
    ComputationDeterminism determinism,
    ComputationAuthorityCeiling authorityCeiling,
    boolean sideEffectFree,
    boolean replaySafe,
    String inputSchemaHash,
    String outputSchemaHash,
    ComputationResourceEnvelope resourceEnvelope,
    String producerId,
    String producerVersion,
    String verifierId,
    String verifierVersion) {
  public ComputationCapabilityDescriptor {
    method = Objects.requireNonNull(method, "method");
    capabilityId = required(capabilityId, "capabilityId");
    capabilityVersion = required(capabilityVersion, "capabilityVersion");
    backendKind = Objects.requireNonNull(backendKind, "backendKind");
    determinism = Objects.requireNonNull(determinism, "determinism");
    authorityCeiling = Objects.requireNonNull(authorityCeiling, "authorityCeiling");
    inputSchemaHash = required(inputSchemaHash, "inputSchemaHash");
    outputSchemaHash = required(outputSchemaHash, "outputSchemaHash");
    resourceEnvelope = Objects.requireNonNull(resourceEnvelope, "resourceEnvelope");
    producerId = required(producerId, "producerId");
    producerVersion = required(producerVersion, "producerVersion");
    verifierId = required(verifierId, "verifierId");
    verifierVersion = required(verifierVersion, "verifierVersion");
    if (producerId.equals(verifierId) && producerVersion.equals(verifierVersion)) {
      throw new IllegalArgumentException("producer and verifier identities must be independent");
    }
    if (backendKind == ComputationBackendKind.SANDBOXED_PYTHON
        && authorityCeiling != ComputationAuthorityCeiling.AUDIT_ONLY
        && authorityCeiling != ComputationAuthorityCeiling.BOUNDED_OBSERVATION) {
      throw new IllegalArgumentException("sandbox authority exceeds the allowed ceiling");
    }
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
