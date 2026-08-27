package io.github.aililuola.mathproofmesh.sidecar;

import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

final class SidecarTestFixtures {
  static final String TOOL_VERSION =
      "mathproofmesh-python-compute/0.8.0;sympy=1.14.0;z3=4.16.0";

  private SidecarTestFixtures() {}

  static Path root() {
    String configured = System.getProperty("maven.multiModuleProjectDirectory");
    Path candidate =
        configured == null || configured.isBlank()
            ? Path.of("").toAbsolutePath().normalize()
            : Path.of(configured).toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isDirectory(candidate.resolve("python-compute-service"))
          && Files.isRegularFile(candidate.resolve("pom.xml"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("could not locate the Maven reactor root");
  }

  static Path python() {
    Path windows = root().resolve(".venv-baseline").resolve("Scripts").resolve("python.exe");
    if (Files.isRegularFile(windows)) {
      return windows;
    }
    return root().resolve(".venv-baseline").resolve("bin").resolve("python");
  }

  static Path service() {
    return root().resolve("python-compute-service").resolve("service.py");
  }

  static Path astValidator() {
    return root().resolve("python-compute-service").resolve("sandbox_ast.py");
  }

  static PythonSidecarWorkerPool workers(Path service) {
    return new PythonSidecarWorkerPool(
        python(), service, 2, Duration.ofSeconds(5));
  }

  static ComputationBroker broker(String runId) {
    PythonSidecarComputationHandler handler =
        new PythonSidecarComputationHandler(
            workers(service()), TOOL_VERSION);
    return new ComputationBroker(
        runId,
        ComputationLimits.defaultsEnabled(),
        new ComputationHandlerRegistry(handler),
        new InMemoryComputationCache());
  }
}
