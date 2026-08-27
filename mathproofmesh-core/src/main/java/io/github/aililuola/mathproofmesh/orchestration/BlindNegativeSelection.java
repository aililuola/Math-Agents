package io.github.aililuola.mathproofmesh.orchestration;

import java.util.List;

/** Bounded negative evidence selected without author or assessment identity. */
public record BlindNegativeSelection(
    List<String> packets,
    int totalCount,
    int omittedCount,
    int omittedMandatoryCount,
    boolean mandatoryContextComplete,
    boolean truncated,
    int usedCharacters,
    int maximumCharacters) {
  public BlindNegativeSelection {
    packets = packets == null ? List.of() : List.copyOf(packets);
    if (totalCount < 0
        || omittedCount < 0
        || omittedMandatoryCount < 0
        || usedCharacters < 0
        || maximumCharacters < 0) {
      throw new IllegalArgumentException("negative selection counts must be nonnegative");
    }
  }

  @Override
  public List<String> packets() {
    return List.copyOf(packets);
  }
}
