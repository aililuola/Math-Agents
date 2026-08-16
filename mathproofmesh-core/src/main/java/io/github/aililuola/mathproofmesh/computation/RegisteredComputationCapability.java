package io.github.aililuola.mathproofmesh.computation;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Set;

public record RegisteredComputationCapability(
    ComputationCapabilityDescriptor descriptor,
    Set<String> allowedArguments,
    boolean acceptsDomains,
    boolean available,
    ComputationProducer producer,
    ComputationCertificateVerifier verifier) {
  @SuppressFBWarnings(
      value = "EC_UNRELATED_TYPES",
      justification =
          "One implementation can implement both service interfaces; identity separation is required.")
  public RegisteredComputationCapability {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    allowedArguments = allowedArguments == null ? Set.of() : Set.copyOf(allowedArguments);
    producer = Objects.requireNonNull(producer, "producer");
    verifier = Objects.requireNonNull(verifier, "verifier");
    if (producer == verifier) {
      throw new IllegalArgumentException("producer and verifier must be separate implementations");
    }
  }

  @Override
  public Set<String> allowedArguments() {
    return Set.copyOf(allowedArguments);
  }
}
