package io.github.aililuola.mathproofmesh.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.computation.ExternalComputationHandler;
import io.github.aililuola.mathproofmesh.computation.HandlerEvidence;
import io.github.aililuola.mathproofmesh.computation.SandboxExecutionError;
import io.github.aililuola.mathproofmesh.computation.SandboxFunctions;
import io.github.aililuola.mathproofmesh.computation.SandboxSettings;
import io.github.aililuola.mathproofmesh.computation.UnsafeProgramError;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Explicitly enabled, container-only handler for custom programs.
 *
 * <p>Its output is always bounded evidence. A container-produced
 * counterexample remains a candidate until a typed checker independently
 * replays it.
 */
public final class SandboxedPythonComputationHandler
    implements ExternalComputationHandler {
  private static final Set<String> ALLOWED_DEPENDENCIES =
      Set.of(
          "collections",
          "decimal",
          "fractions",
          "functools",
          "itertools",
          "math");

  private final SandboxSettings settings;
  private final PythonSandboxAstValidator astValidator;
  private final String dockerExecutable;
  private final Path tempRoot;

  public SandboxedPythonComputationHandler(
      SandboxSettings settings,
      PythonSandboxAstValidator astValidator,
      String dockerExecutable,
      Path tempRoot) {
    this.settings = java.util.Objects.requireNonNull(settings, "settings");
    this.astValidator =
        java.util.Objects.requireNonNull(astValidator, "astValidator");
    if (dockerExecutable == null || dockerExecutable.isBlank()) {
      throw new IllegalArgumentException("dockerExecutable is required");
    }
    this.dockerExecutable = dockerExecutable;
    this.tempRoot = tempRoot.toAbsolutePath().normalize();
    if (!Files.isDirectory(this.tempRoot)) {
      throw new IllegalArgumentException("tempRoot must be an existing directory");
    }
  }

  @Override
  public boolean supports(ComputationMethod method) {
    return settings.enabled() && method == ComputationMethod.SANDBOXED_PYTHON;
  }

  @Override
  public String toolIdentity(ComputationMethod method) {
    if (method != ComputationMethod.SANDBOXED_PYTHON) {
      throw new IllegalArgumentException("unsupported sandbox method");
    }
    return "docker-sandbox/0.8.0/" + settings.image();
  }

  @Override
  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "The command is assembled as an argument list by SandboxFunctions "
              + "from a digest-pinned image, server-owned executable, and "
              + "server-created work directory; model source is mounted as data.")
  public HandlerEvidence execute(ExperimentSpec spec, ExperimentProgram program) {
    if (!settings.enabled()) {
      throw new IllegalStateException("sandboxed Python is disabled");
    }
    if (spec.method() != ComputationMethod.SANDBOXED_PYTHON) {
      throw new IllegalArgumentException("unsupported sandbox method");
    }
    if (program == null || !spec.experimentId().equals(program.experimentId())) {
      throw new IllegalArgumentException(
          "sandbox program must be bound to the experiment");
    }
    Set<String> dependencies = dependencyRoots(program.dependencies());
    astValidator.validate(program.source(), dependencies);
    SandboxFunctions.validateProgramSchemas(program);
    JsonNode rawInput = spec.arguments().get("input");
    if (!(rawInput instanceof ObjectNode inputObject)) {
      throw new IllegalArgumentException("sandbox input must be a JSON object");
    }
    ObjectNode input = inputObject.deepCopy();
    input.put("seed", spec.seed());
    SandboxFunctions.validateJsonObject(input, program.inputSchema(), "input");

    Path workDirectory = null;
    try {
      workDirectory =
          Files.createTempDirectory(tempRoot, "mathproofmesh_experiment_");
      Path programPath = workDirectory.resolve("program.py");
      Files.writeString(programPath, program.source(), StandardCharsets.UTF_8);
      List<String> command =
          SandboxFunctions.buildDockerCommand(
              dockerExecutable, settings, workDirectory);
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.environment().clear();
      Process process = builder.start();
      byte[] request =
          ContractObjectMapper.write(input).getBytes(StandardCharsets.UTF_8);
      try (ExecutorService executor =
          Executors.newVirtualThreadPerTaskExecutor()) {
        Future<BoundedBytes> stdout =
            executor.submit(
                () ->
                    readBounded(
                        process.getInputStream(), settings.maxOutputChars()));
        Future<BoundedBytes> stderr =
            executor.submit(
                () ->
                    readBounded(
                        process.getErrorStream(), settings.maxOutputChars()));
        process.getOutputStream().write(request);
        process.getOutputStream().close();
        if (!process.waitFor(
            settings.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
          destroy(process);
          throw new SandboxExecutionError(
              "sandbox timed out after " + settings.timeout(),
              null,
              "",
              "");
        }
        BoundedBytes out = await(stdout);
        BoundedBytes err = await(stderr);
        String outText = decode(out);
        String errText = decode(err);
        if (out.exceeded() || err.exceeded()) {
          throw new SandboxExecutionError(
              "sandbox output exceeded its bound",
              process.exitValue(),
              outText,
              errText);
        }
        if (process.exitValue() != 0) {
          throw new SandboxExecutionError(
              "sandbox exited with code " + process.exitValue(),
              process.exitValue(),
              outText,
              errText);
        }
        JsonNode parsed = ContractObjectMapper.parseTree(outText);
        ObjectNode output =
            SandboxFunctions.validateJsonObject(
                parsed, program.outputSchema(), "output");
        return boundedEvidence(output);
      }
    } catch (IOException exception) {
      throw new SandboxExecutionError(
          "sandbox process could not be started",
          null,
          "",
          exception.getClass().getSimpleName());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SandboxExecutionError(
          "sandbox execution was interrupted", null, "", "");
    } finally {
      if (workDirectory != null) {
        deleteTemporaryDirectory(workDirectory);
      }
    }
  }

  private static HandlerEvidence boundedEvidence(ObjectNode output) {
    String rawOutcome = output.path("outcome").asText("inconclusive");
    int cases =
        output.path("cases_checked").canConvertToInt()
            ? Math.max(0, output.path("cases_checked").intValue())
            : 0;
    boolean exact = output.path("exact_arithmetic").asBoolean(false);
    ObjectNode scope =
        output.get("scope") instanceof ObjectNode value
            ? value.deepCopy()
            : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    if ("counterexample_found".equals(rawOutcome)) {
      if (output.get("counterexample") instanceof ObjectNode candidate) {
        scope.set("counterexample_candidate", candidate.deepCopy());
      }
      return new HandlerEvidence(
          ExperimentOutcome.INCONCLUSIVE,
          EvidenceStrength.HEURISTIC,
          scope,
          null,
          null,
          exact,
          cases,
          false,
          List.of(
              "Sandbox output is only a counterexample candidate until a typed checker replays it."),
          output);
    }
    if ("not_refuted".equals(rawOutcome) || "certified".equals(rawOutcome)) {
      ObjectNode certificate =
          output.get("certificate") instanceof ObjectNode value
              ? value.deepCopy()
              : null;
      return new HandlerEvidence(
          ExperimentOutcome.NOT_REFUTED,
          EvidenceStrength.BOUNDED_EVIDENCE,
          scope,
          null,
          certificate,
          exact,
          cases,
          false,
          List.of(
              "Sandbox enumeration is bounded evidence and cannot close a Fact gate."),
          output);
    }
    return HandlerEvidence.inconclusive(
        "Sandbox program returned no admissible evidence.", scope);
  }

  private static Set<String> dependencyRoots(List<String> dependencies) {
    Set<String> roots = new HashSet<>();
    for (String dependency : dependencies) {
      String root = dependency.split("[.=<>=!~]", 2)[0].trim();
      if (!ALLOWED_DEPENDENCIES.contains(root)) {
        throw new UnsafeProgramError(
            "program declares a non-whitelisted dependency");
      }
      roots.add(root);
    }
    return Set.copyOf(roots);
  }

  private static BoundedBytes readBounded(InputStream stream, int limit)
      throws IOException {
    ByteArrayOutputStream output =
        new ByteArrayOutputStream(Math.min(limit, 8_192));
    byte[] buffer = new byte[8_192];
    int total = 0;
    int count;
    while ((count = stream.read(buffer)) >= 0) {
      if (count == 0) {
        continue;
      }
      int retained = Math.min(count, Math.max(0, limit - total));
      if (retained > 0) {
        output.write(buffer, 0, retained);
      }
      total += count;
    }
    return new BoundedBytes(output.toByteArray(), total > limit);
  }

  private static BoundedBytes await(Future<BoundedBytes> future) {
    try {
      return future.get(2, TimeUnit.SECONDS);
    } catch (ExecutionException exception) {
      throw new SandboxExecutionError(
          "sandbox stream read failed", null, "", "");
    } catch (java.util.concurrent.TimeoutException exception) {
      throw new SandboxExecutionError(
          "sandbox stream did not close", null, "", "");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SandboxExecutionError(
          "sandbox stream read was interrupted", null, "", "");
    }
  }

  private static String decode(BoundedBytes value) {
    return new String(value.bytes(), StandardCharsets.UTF_8)
        .replaceAll(
            "(?i)(api[_-]?key|password|secret|token)\\s*[:=]\\s*[^\\s,;]+",
            "$1=[REDACTED]");
  }

  private static void destroy(Process process) {
    process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  private static void deleteTemporaryDirectory(Path directory) {
    try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
      paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // Best effort within the dedicated temporary directory.
                }
              });
    } catch (IOException ignored) {
      // Best effort cleanup; no caller-owned path is traversed.
    }
  }

  private record BoundedBytes(byte[] bytes, boolean exceeded) {}
}
