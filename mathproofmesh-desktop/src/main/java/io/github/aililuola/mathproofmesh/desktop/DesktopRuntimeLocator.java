package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.computation.SandboxFunctions;
import io.github.aililuola.mathproofmesh.config.ConfigValidationException;
import io.github.aililuola.mathproofmesh.config.StrictYamlConfigLoader;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Resolves only server-owned profile, sidecar, Python, and Docker locations. */
final class DesktopRuntimeLocator {
  static final String SIDECAR_TOOL_VERSION =
      "mathproofmesh-python-compute/0.8.0;sympy=1.14.0;z3=4.16.0";

  private static final Set<String> PROFILE_FILES =
      Set.of(
          "deepseek-v4-pro-smoke.yaml",
          "deepseek-v4-pro.yaml",
          "topology-active.yaml",
          "proof-control-shadow.yaml",
          "proof-control-active.yaml");

  private final Path developmentRoot;
  private final Path packagedContent;

  DesktopRuntimeLocator() {
    this(findDevelopmentRoot(), findPackagedContent());
  }

  DesktopRuntimeLocator(Path developmentRoot, Path packagedContent) {
    this.developmentRoot = normalizeDirectory(developmentRoot);
    this.packagedContent = normalizeDirectory(packagedContent);
  }

  SystemConfig loadProfile(String profileFile) {
    if (!PROFILE_FILES.contains(profileFile)) {
      throw new IllegalArgumentException("unsupported desktop profile file");
    }
    Path path = firstFile(profileCandidates(profileFile));
    if (path == null) {
      throw new ConfigValidationException("packaged desktop profile is missing");
    }
    return new StrictYamlConfigLoader().load(path);
  }

  Path sidecarService() {
    return requiredFile(
        "Python computation sidecar",
        child(developmentRoot, "python-compute-service", "service.py"),
        child(packagedContent, "sidecar", "service.py"));
  }

  Path sandboxValidator() {
    return requiredFile(
        "Python sandbox AST validator",
        child(developmentRoot, "python-compute-service", "sandbox_ast.py"),
        child(packagedContent, "sidecar", "sandbox_ast.py"));
  }

  Path pythonExecutable() {
    List<Path> candidates = new ArrayList<>();
    String override = System.getenv("MATHPROOFMESH_PYTHON");
    if (override != null && !override.isBlank()) {
      candidates.add(Path.of(override));
    }
    addIfPresent(candidates, child(packagedContent, "sidecar-runtime", "python.exe"));
    addIfPresent(
        candidates, child(packagedContent, "sidecar-runtime", "Scripts", "python.exe"));
    addIfPresent(candidates, child(packagedContent, "sidecar-runtime", "bin", "python3"));
    addIfPresent(
        candidates, child(developmentRoot, ".venv-baseline", "Scripts", "python.exe"));
    addIfPresent(
        candidates,
        child(
            developmentRoot,
            ".cache",
            "phase08-sidecar-venv-verified",
            "Scripts",
            "python.exe"));
    Path onPath = executableOnPath(isWindows() ? "python.exe" : "python3");
    if (onPath != null) {
      candidates.add(onPath);
    }
    Path selected = firstFile(candidates);
    if (selected == null) {
      throw new IllegalStateException("locked Python sidecar runtime is unavailable");
    }
    return selected;
  }

  String dockerExecutable() {
    String override = System.getenv("MATHPROOFMESH_DOCKER");
    if (override != null && !override.isBlank()) {
      Path selected = Path.of(override).toAbsolutePath().normalize();
      if (!Files.isRegularFile(selected)) {
        throw new IllegalStateException("MATHPROOFMESH_DOCKER is not a regular file");
      }
      return selected.toString();
    }
    Path discovered = executableOnPath(isWindows() ? "docker.exe" : "docker");
    String local = System.getenv("LOCALAPPDATA");
    String programs = System.getenv("ProgramFiles");
    String selected =
        SandboxFunctions.findDockerExecutable(
            discovered == null ? null : discovered.toString(),
            local == null || local.isBlank() ? null : Path.of(local),
            programs == null || programs.isBlank() ? null : Path.of(programs));
    if (selected == null) {
      throw new IllegalStateException("Docker CLI is unavailable for sandboxed Python");
    }
    return selected;
  }

  private List<Path> profileCandidates(String profileFile) {
    List<Path> candidates = new ArrayList<>();
    addIfPresent(candidates, child(developmentRoot, "config", profileFile));
    addIfPresent(candidates, child(packagedContent, "profiles", profileFile));
    return candidates;
  }

  private static Path requiredFile(String label, Path... candidates) {
    List<Path> present = new ArrayList<>();
    for (Path candidate : candidates) {
      addIfPresent(present, candidate);
    }
    Path selected = firstFile(present);
    if (selected == null) {
      throw new IllegalStateException(label + " is unavailable");
    }
    return selected;
  }

  private static Path firstFile(List<Path> candidates) {
    return candidates.stream()
        .filter(java.util.Objects::nonNull)
        .map(path -> path.toAbsolutePath().normalize())
        .filter(Files::isRegularFile)
        .findFirst()
        .orElse(null);
  }

  private static void addIfPresent(List<Path> candidates, Path candidate) {
    if (candidate != null) {
      candidates.add(candidate);
    }
  }

  private static Path executableOnPath(String name) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return null;
    }
    for (String part : path.split(java.io.File.pathSeparator)) {
      String normalized = part.strip().replaceAll("^\"|\"$", "");
      if (normalized.isEmpty()) {
        continue;
      }
      Path candidate = Path.of(normalized).resolve(name).toAbsolutePath().normalize();
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private static Path findDevelopmentRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (Files.isRegularFile(current.resolve("config").resolve("proof-control-active.yaml"))
          && Files.isDirectory(current.resolve("python-compute-service"))) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  private static Path findPackagedContent() {
    String appPath = System.getProperty("jpackage.app-path");
    if (appPath == null || appPath.isBlank()) {
      return null;
    }
    Path executable = Path.of(appPath).toAbsolutePath().normalize();
    Path parent = executable.getParent();
    return parent == null ? null : parent.resolve("app");
  }

  private static Path child(Path root, String... names) {
    if (root == null) {
      return null;
    }
    Path result = root;
    for (String name : names) {
      result = result.resolve(name);
    }
    return result;
  }

  private static Path normalizeDirectory(Path path) {
    if (path == null) {
      return null;
    }
    Path normalized = path.toAbsolutePath().normalize();
    return Files.isDirectory(normalized) ? normalized : null;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "")
        .toLowerCase(Locale.ROOT)
        .contains("win");
  }
}
