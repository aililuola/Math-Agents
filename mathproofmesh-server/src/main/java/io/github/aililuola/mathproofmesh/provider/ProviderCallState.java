package io.github.aililuola.mathproofmesh.provider;

import java.util.EnumSet;
import java.util.Set;

public enum ProviderCallState {
  PLANNED,
  DISPATCHED,
  STREAMING,
  SUCCEEDED,
  FAILED,
  AMBIGUOUS,
  CANCELLED;

  public boolean canTransitionTo(ProviderCallState target) {
    return allowedTargets().contains(target);
  }

  public boolean terminal() {
    return this == SUCCEEDED
        || this == FAILED
        || this == AMBIGUOUS
        || this == CANCELLED;
  }

  private Set<ProviderCallState> allowedTargets() {
    return switch (this) {
      case PLANNED -> EnumSet.of(DISPATCHED, CANCELLED);
      case DISPATCHED ->
          EnumSet.of(STREAMING, SUCCEEDED, FAILED, AMBIGUOUS, CANCELLED);
      case STREAMING ->
          EnumSet.of(SUCCEEDED, FAILED, AMBIGUOUS, CANCELLED);
      case SUCCEEDED, FAILED, AMBIGUOUS, CANCELLED ->
          EnumSet.noneOf(ProviderCallState.class);
    };
  }
}
