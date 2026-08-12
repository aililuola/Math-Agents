package io.github.aililuola.mathproofmesh.desktop;

import java.util.Set;

/** Persisted non-secret desktop preferences. */
public record DesktopSettings(
    String selectedProfile, boolean sandboxEnabled, boolean rememberCredentials) {
  public static final Set<String> PROFILES =
      Set.of("smoke", "formal", "active", "proof_control_shadow", "proof_control_active");

  public DesktopSettings {
    selectedProfile =
        selectedProfile == null || selectedProfile.isBlank() ? "smoke" : selectedProfile.trim();
    if (!PROFILES.contains(selectedProfile)) {
      throw new IllegalArgumentException("unsupported desktop profile");
    }
  }

  public static DesktopSettings defaults() {
    return new DesktopSettings("smoke", true, true);
  }
}
