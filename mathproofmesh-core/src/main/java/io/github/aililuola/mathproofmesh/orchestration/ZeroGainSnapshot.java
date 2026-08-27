package io.github.aililuola.mathproofmesh.orchestration;

public record ZeroGainSnapshot(int schemaVersion, ZeroGainState state) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public ZeroGainSnapshot {
    if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported zero-gain snapshot schema");
    }
    state = state == null ? ZeroGainState.empty() : state;
  }

  public static ZeroGainSnapshot empty() {
    return new ZeroGainSnapshot(CURRENT_SCHEMA_VERSION, ZeroGainState.empty());
  }
}
