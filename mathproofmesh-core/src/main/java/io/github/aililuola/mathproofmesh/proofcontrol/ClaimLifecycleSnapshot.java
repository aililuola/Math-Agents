package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.LinkedHashMap;
import java.util.Map;

public record ClaimLifecycleSnapshot(
    Map<String, ClaimLifecycleController.Entry> entries) {

  public ClaimLifecycleSnapshot {
    entries = entries == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(entries));
  }

  public static ClaimLifecycleSnapshot empty() {
    return new ClaimLifecycleSnapshot(Map.of());
  }
}
