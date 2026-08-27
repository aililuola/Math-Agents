package io.github.aililuola.mathproofmesh.orchestration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.aililuola.mathproofmesh.contract.ActionKind;
import java.util.Objects;

/** Stable identity for per-target zero-gain accounting. */
public record TargetMechanismKey(
    String targetId,
    String strategySignature,
    ActionKind actionKind,
    String mechanismSignature) implements Comparable<TargetMechanismKey> {
  private static final String SEPARATOR = "\u001f";

  public TargetMechanismKey {
    targetId = required(targetId, "targetId");
    strategySignature = required(strategySignature, "strategySignature");
    actionKind = Objects.requireNonNull(actionKind, "actionKind");
    mechanismSignature = required(mechanismSignature, "mechanismSignature");
    if (targetId.contains(SEPARATOR)
        || strategySignature.contains(SEPARATOR)
        || mechanismSignature.contains(SEPARATOR)) {
      throw new IllegalArgumentException("target mechanism identity contains a reserved separator");
    }
  }

  @JsonValue
  public String stableIdentity() {
    return targetId + SEPARATOR + strategySignature + SEPARATOR + actionKind.value() + SEPARATOR
        + mechanismSignature;
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static TargetMechanismKey fromStableIdentity(String value) {
    String normalized = required(value, "stableIdentity");
    String[] parts = normalized.split(SEPARATOR, -1);
    if (parts.length != 4) {
      throw new IllegalArgumentException("invalid target mechanism stable identity");
    }
    return new TargetMechanismKey(
        parts[0], parts[1], ActionKind.fromValue(parts[2]), parts[3]);
  }

  @Override
  public int compareTo(TargetMechanismKey other) {
    return stableIdentity().compareTo(other.stableIdentity());
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
