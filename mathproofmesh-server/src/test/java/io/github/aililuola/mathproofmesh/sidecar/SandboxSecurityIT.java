package io.github.aililuola.mathproofmesh.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.computation.SandboxFunctions;
import io.github.aililuola.mathproofmesh.computation.SandboxSettings;
import io.github.aililuola.mathproofmesh.computation.UnsafeProgramError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SandboxSecurityIT {

  @Test
  void sandbox_is_disabled_by_default_and_requires_a_digest() {
    SandboxSettings disabled = SandboxSettings.disabled();
    assertThat(disabled.enabled()).isFalse();

    assertThatThrownBy(
            () ->
                new SandboxSettings(
                    true,
                    "python:3.14",
                    Duration.ofSeconds(1),
                    128,
                    0.5,
                    16,
                    1024))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
  }

  @Test
  void docker_command_has_network_filesystem_user_and_resource_isolation(
      @org.junit.jupiter.api.io.TempDir Path work) {
    SandboxSettings settings =
        new SandboxSettings(
            true,
            "python@sha256:"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            Duration.ofSeconds(2),
            128,
            0.5,
            16,
            2048);

    String command =
        String.join(" ", SandboxFunctions.buildDockerCommand("docker", settings, work));
    assertThat(command)
        .contains("--network none")
        .contains("--read-only")
        .contains("--user 65532:65532")
        .contains("--cap-drop ALL")
        .contains("--security-opt no-new-privileges")
        .contains("--memory 128m")
        .contains("--pids-limit 16");
  }

  @Test
  void ast_gate_rejects_file_network_process_reflection_and_dynamic_execution() {
    PythonSandboxAstValidator validator =
        new PythonSandboxAstValidator(python(), validatorScript());

    for (String source :
        java.util.List.of(
            "def run(data):\n    return open('/etc/passwd').read()\n",
            "import socket\ndef run(data):\n    return socket.socket()\n",
            "import subprocess\ndef run(data):\n    return subprocess.run(['whoami'])\n",
            "def run(data):\n    return data.__class__\n",
            "def run(data):\n    return eval(data['source'])\n")) {
      assertThatThrownBy(() -> validator.validate(source, dependencies(source)))
          .isInstanceOf(UnsafeProgramError.class);
    }
  }

  @Test
  void ast_gate_accepts_only_declared_safe_imports() {
    PythonSandboxAstValidator validator =
        new PythonSandboxAstValidator(python(), validatorScript());
    String source =
        "from fractions import Fraction\n"
            + "def run(data):\n"
            + "    value = Fraction(data['n'], data['d'])\n"
            + "    return {'value': value}\n";

    assertThat(validator.validate(source, Set.of("fractions")))
        .containsExactly("fractions");
    assertThatThrownBy(() -> validator.validate(source, Set.of()))
        .isInstanceOf(UnsafeProgramError.class)
        .hasMessageContaining("exactly match");
  }

  private static Set<String> dependencies(String source) {
    if (source.contains("socket")) {
      return Set.of("socket");
    }
    if (source.contains("subprocess")) {
      return Set.of("subprocess");
    }
    return Set.of();
  }

  private static Path python() {
    Path root = Path.of("").toAbsolutePath().normalize();
    Path candidate = root.resolve(".venv-baseline/Scripts/python.exe");
    if (!Files.isRegularFile(candidate)) {
      candidate = root.resolve("../.venv-baseline/Scripts/python.exe").normalize();
    }
    return candidate;
  }

  private static Path validatorScript() {
    Path root = Path.of("").toAbsolutePath().normalize();
    Path candidate = root.resolve("python-compute-service/sandbox_ast.py");
    if (!Files.isRegularFile(candidate)) {
      candidate = root.resolve("../python-compute-service/sandbox_ast.py").normalize();
    }
    return candidate;
  }
}
