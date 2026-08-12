package io.github.aililuola.mathproofmesh.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record DomainEvent(
    String eventId,
    String runId,
    String aggregateType,
    String aggregateId,
    long aggregateVersion,
    String eventType,
    String payload,
    String eventHash
) {
  public DomainEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(eventHash, "eventHash");
    if (aggregateVersion < 0) {
      throw new IllegalArgumentException("aggregateVersion must be non-negative");
    }
    if (!eventHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("eventHash must be lowercase SHA-256");
    }
  }

  public static DomainEvent create(
      String eventId,
      String runId,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String eventType,
      String payload) {
    String material =
        String.join(
            "\u0000",
            eventId,
            runId,
            aggregateType,
            aggregateId,
            Long.toString(aggregateVersion),
            eventType,
            payload);
    return new DomainEvent(
        eventId,
        runId,
        aggregateType,
        aggregateId,
        aggregateVersion,
        eventType,
        payload,
        sha256(material.getBytes(StandardCharsets.UTF_8)));
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the JDK", exception);
    }
  }
}
