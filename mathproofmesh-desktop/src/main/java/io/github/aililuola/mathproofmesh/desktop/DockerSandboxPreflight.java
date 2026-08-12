package io.github.aililuola.mathproofmesh.desktop;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Fail-closed Docker daemon and pinned-image preflight for live computations. */
@SuppressFBWarnings(
    value = "COMMAND_INJECTION",
    justification =
        "The executable is a validated regular file, the image must use a literal SHA-256 "
            + "digest, and ProcessBuilder receives separate arguments without a command shell.")
final class DockerSandboxPreflight {
  private static final int MAX_OUTPUT_BYTES = 16_384;
  private static final Set<String> PASSTHROUGH_ENV =
      Set.of(
          "systemroot",
          "windir",
          "appdata",
          "localappdata",
          "userprofile",
          "docker_host",
          "docker_context");

  Result verify(String dockerExecutable, String pinnedImage) {
    Path executable = Path.of(dockerExecutable).toAbsolutePath().normalize();
    if (!Files.isRegularFile(executable)) {
      throw new IllegalStateException("Docker sandbox executable is not a regular file");
    }
    if (pinnedImage == null
        || !pinnedImage.matches("[a-z0-9][a-z0-9._/-]*@sha256:[a-f0-9]{64}")) {
      throw new IllegalStateException("Docker sandbox image is not pinned by SHA-256 digest");
    }
    String serverVersion =
        execute(
            List.of(executable.toString(), "version", "--format", "{{.Server.Version}}"),
            Duration.ofSeconds(20));
    String imageId =
        execute(
            List.of(
                executable.toString(),
                "image",
                "inspect",
                pinnedImage,
                "--format",
                "{{.Id}}"),
            Duration.ofSeconds(20));
    if (serverVersion.isBlank() || !imageId.startsWith("sha256:")) {
      throw new IllegalStateException("Docker sandbox preflight returned invalid metadata");
    }
    return new Result(serverVersion, imageId);
  }

  private static String execute(List<String> command, Duration timeout) {
    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectErrorStream(true);
      configureEnvironment(builder.environment());
      process = builder.start();
    } catch (IOException exception) {
      throw new IllegalStateException("Docker sandbox process could not be started", exception);
    }
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<byte[]> output = executor.submit(() -> readBounded(process.getInputStream()));
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Docker sandbox preflight timed out");
      }
      String text = new String(await(output), StandardCharsets.UTF_8).strip();
      if (process.exitValue() != 0) {
        throw new IllegalStateException("Docker sandbox preflight failed: " + bounded(text));
      }
      return bounded(text);
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Docker sandbox preflight was interrupted", exception);
    }
  }

  private static byte[] readBounded(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[2_048];
    int total = 0;
    int count;
    while ((count = input.read(buffer)) >= 0) {
      if (count == 0) {
        continue;
      }
      total += count;
      if (total > MAX_OUTPUT_BYTES) {
        throw new IllegalStateException("Docker sandbox preflight output exceeded its bound");
      }
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static byte[] await(Future<byte[]> output) {
    try {
      return output.get(2, TimeUnit.SECONDS);
    } catch (ExecutionException exception) {
      throw new IllegalStateException(
          "Docker sandbox preflight output could not be read", exception.getCause());
    } catch (java.util.concurrent.TimeoutException exception) {
      throw new IllegalStateException("Docker sandbox preflight output did not close", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Docker sandbox preflight output was interrupted", exception);
    }
  }

  private static void configureEnvironment(Map<String, String> environment) {
    Map<String, String> inherited = Map.copyOf(environment);
    environment.clear();
    inherited.forEach(
        (name, value) -> {
          if (PASSTHROUGH_ENV.contains(name.toLowerCase(Locale.ROOT))) {
            environment.put(name, value);
          }
        });
  }

  private static String bounded(String value) {
    String singleLine = value.replace('\r', ' ').replace('\n', ' ');
    return singleLine.length() <= 1_000 ? singleLine : singleLine.substring(0, 1_000);
  }

  record Result(String serverVersion, String imageId) {}
}
