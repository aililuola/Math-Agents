package io.github.aililuola.mathproofmesh.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.computation.UnsafeProgramError;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Calls the trusted CPython AST validator before any optional container run. */
public final class PythonSandboxAstValidator {
  private static final int MAX_SOURCE_BYTES = 256_000;
  private static final int MAX_DIAGNOSTIC_BYTES = 4_096;

  private final Path pythonExecutable;
  private final Path validatorScript;
  private final Path workingDirectory;

  public PythonSandboxAstValidator(Path pythonExecutable, Path validatorScript) {
    this.pythonExecutable = requireFile(pythonExecutable, "python executable");
    this.validatorScript = requireFile(validatorScript, "AST validator");
    this.workingDirectory =
        java.util.Objects.requireNonNull(
            this.validatorScript.getParent(), "AST validator parent");
  }

  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "Both command arguments are constructor-validated regular files; "
              + "program source is sent on stdin and is never part of the command.")
  public Set<String> validate(String source, Set<String> declaredDependencies) {
    if (source == null
        || source.isBlank()
        || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
      throw new UnsafeProgramError("program source is empty or oversized");
    }
    Set<String> declared =
        declaredDependencies == null ? Set.of() : Set.copyOf(declaredDependencies);
    Process process;
    try {
      ProcessBuilder builder =
          new ProcessBuilder(
              pythonExecutable.toString(), validatorScript.toString());
      builder.directory(workingDirectory.toFile());
      Map<String, String> environment = builder.environment();
      String systemRoot = environment.get("SystemRoot");
      String windir = environment.get("WINDIR");
      environment.clear();
      if (systemRoot != null) {
        environment.put("SystemRoot", systemRoot);
      }
      if (windir != null) {
        environment.put("WINDIR", windir);
      }
      environment.put("PYTHONUTF8", "1");
      environment.put("PYTHONIOENCODING", "utf-8");
      environment.put("PYTHONNOUSERSITE", "1");
      environment.put("PYTHONDONTWRITEBYTECODE", "1");
      process = builder.start();
      process.getOutputStream().write(source.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
      if (!process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
        destroy(process);
        throw new UnsafeProgramError("AST validation timed out");
      }
      byte[] stdout = process.getInputStream().readNBytes(MAX_DIAGNOSTIC_BYTES + 1);
      byte[] stderr = process.getErrorStream().readNBytes(MAX_DIAGNOSTIC_BYTES + 1);
      if (stdout.length > MAX_DIAGNOSTIC_BYTES
          || stderr.length > MAX_DIAGNOSTIC_BYTES) {
        throw new UnsafeProgramError("AST validator output exceeded its bound");
      }
      JsonNode result =
          ContractObjectMapper.parseTree(
              new String(stdout, StandardCharsets.UTF_8));
      if (process.exitValue() != 0 || !result.path("valid").asBoolean(false)) {
        String message = result.path("error").asText("program failed AST validation");
        throw new UnsafeProgramError(bounded(message));
      }
      JsonNode imports = result.get("imports");
      if (imports == null || !imports.isArray()) {
        throw new UnsafeProgramError("AST validator returned an invalid response");
      }
      Set<String> actual = new HashSet<>();
      for (JsonNode item : imports) {
        if (!item.isTextual()) {
          throw new UnsafeProgramError("AST validator returned an invalid import");
        }
        actual.add(item.textValue());
      }
      if (!actual.equals(declared)) {
        throw new UnsafeProgramError(
            "program dependencies must exactly match its imported modules");
      }
      return Set.copyOf(actual);
    } catch (IOException exception) {
      throw new UnsafeProgramError(
          "could not execute the trusted AST validator: "
              + exception.getClass().getSimpleName());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new UnsafeProgramError("AST validation was interrupted");
    } catch (RuntimeException exception) {
      if (exception instanceof UnsafeProgramError unsafe) {
        throw unsafe;
      }
      throw new UnsafeProgramError("AST validator returned malformed JSON");
    }
  }

  private static void destroy(Process process) {
    process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  private static String bounded(String value) {
    String normalized = value.replace('\r', ' ').replace('\n', ' ');
    return normalized.length() <= 1_000
        ? normalized
        : normalized.substring(0, 1_000);
  }

  private static Path requireFile(Path value, String label) {
    if (value == null || !Files.isRegularFile(value.toAbsolutePath().normalize())) {
      throw new IllegalArgumentException(label + " is not a regular file");
    }
    return value.toAbsolutePath().normalize();
  }
}
