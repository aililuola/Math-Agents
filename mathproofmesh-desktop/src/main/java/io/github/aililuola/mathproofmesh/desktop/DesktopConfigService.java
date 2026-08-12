package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.computation.SandboxFunctions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies user-writable paths, non-secret settings, and server-side credential injection. */
public final class DesktopConfigService {
  private static final Map<String, String> PROFILE_FILES =
      Map.of(
          "smoke", "deepseek-v4-pro-smoke.yaml",
          "formal", "deepseek-v4-pro.yaml",
          "active", "topology-active.yaml",
          "proof_control_shadow", "proof-control-shadow.yaml",
          "proof_control_active", "proof-control-active.yaml");

  private final DesktopPaths paths;
  private final CredentialVault credentials;

  public DesktopConfigService(DesktopPaths paths, CredentialVault credentials) {
    this.paths = Objects.requireNonNull(paths, "paths");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
  }

  public PreparedDesktopConfig build(String profile, DesktopSettings settings) {
    String safeProfile = DesktopApiModel.safeProfile(profile);
    Map<String, String> keys = new LinkedHashMap<>();
    for (int index = 1; index <= 5; index++) {
      String name = "DEEPSEEK_AGENT_" + index + "_KEY";
      String value = credentials.get(name);
      if (value != null) {
        keys.put(name, value);
      }
    }
    return new PreparedDesktopConfig(
        safeProfile,
        PROFILE_FILES.get(safeProfile),
        paths.root(),
        paths.runs(),
        paths.learning(),
        settings.sandboxEnabled(),
        keys);
  }

  public List<Map<String, Object>> profileSummaries(DesktopSettings settings) {
    List<Map<String, Object>> summaries = new ArrayList<>();
    for (String profile : List.of(
        "smoke", "formal", "active", "proof_control_shadow", "proof_control_active")) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", profile);
      entry.put("label", profile.replace('_', ' '));
      entry.put("filename", PROFILE_FILES.get(profile));
      entry.put("agents", 5);
      entry.put("max_calls", "smoke".equals(profile) ? 20 : 200);
      entry.put("max_tokens", "smoke".equals(profile) ? 32_000 : 400_000);
      entry.put("max_cost_usd", "smoke".equals(profile) ? 0.25 : 10.0);
      entry.put("sandbox_configured", true);
      entry.put("sandbox_effective", settings.sandboxEnabled());
      entry.put("selected", profile.equals(settings.selectedProfile()));
      summaries.add(Collections.unmodifiableMap(entry));
    }
    return List.copyOf(summaries);
  }

  public boolean dockerAvailable() {
    String discovered = null;
    String path = System.getenv("PATH");
    if (path != null) {
      for (String part : path.split(java.io.File.pathSeparator)) {
        String normalized = part.strip().replaceAll("^\"|\"$", "");
        if (normalized.isEmpty()) {
          continue;
        }
        Path candidate =
            Path.of(normalized).resolve(isWindows() ? "docker.exe" : "docker");
        if (Files.isRegularFile(candidate)) {
          discovered = candidate.toAbsolutePath().normalize().toString();
          break;
        }
      }
    }
    String local = System.getenv("LOCALAPPDATA");
    String programs = System.getenv("ProgramFiles");
    return SandboxFunctions.findDockerExecutable(
            discovered,
            local == null || local.isBlank() ? null : Path.of(local),
            programs == null || programs.isBlank() ? null : Path.of(programs))
        != null;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }

  public record PreparedDesktopConfig(
      String profile,
      String profileFile,
      Path projectRoot,
      Path runRoot,
      Path learningRoot,
      boolean sandboxEnabled,
      Map<String, String> injectedCredentials) {
    public PreparedDesktopConfig {
      Objects.requireNonNull(profile, "profile");
      Objects.requireNonNull(profileFile, "profileFile");
      Objects.requireNonNull(projectRoot, "projectRoot");
      Objects.requireNonNull(runRoot, "runRoot");
      Objects.requireNonNull(learningRoot, "learningRoot");
      injectedCredentials = Map.copyOf(injectedCredentials);
    }
  }
}
