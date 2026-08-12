package io.github.aililuola.mathproofmesh.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.computation.UnsafeProgramError;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PythonSidecarProtocolTest {

  @Test
  void valid_request_uses_versioned_one_line_stdio_protocol() {
    ObjectNode response =
        SidecarTestFixtures.workers(SidecarTestFixtures.service())
            .execute(
                "request-valid",
                "sympy_simplify",
                object().put("expression", "x-x"),
                limits());

    assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
    assertThat(response.path("protocol_version").asText()).isEqualTo("1.0");
    assertThat(response.path("request_id").asText()).isEqualTo("request-valid");
    assertThat(response.path("result").path("outcome").asText())
        .isEqualTo("certified");
  }

  @Test
  void unknown_operations_are_rejected_without_execution() {
    ObjectNode response =
        SidecarTestFixtures.workers(SidecarTestFixtures.service())
            .execute("request-unknown", "unknown_operation", object(), limits());

    assertThat(response.path("result").isNull()).isTrue();
    assertThat(response.path("error").path("code").intValue()).isEqualTo(-32601);
  }

  @Test
  void expression_injection_is_rejected_by_the_ast_whitelist() {
    ObjectNode response =
        SidecarTestFixtures.workers(SidecarTestFixtures.service())
            .execute(
                "request-injection",
                "sympy_simplify",
                object().put("expression", "__import__('os').system('whoami')"),
                limits());

    assertThat(response.path("error").path("code").intValue()).isEqualTo(-32602);
    assertThat(response.path("result").isNull()).isTrue();
  }

  @Test
  void malformed_response_is_rejected(@TempDir Path temporary) throws Exception {
    Path script = script(temporary, "print('not-json')\n");
    PythonSidecarWorkerPool workers = SidecarTestFixtures.workers(script);

    assertThatThrownBy(
            () ->
                workers.execute(
                    "malformed", "sympy_simplify", object(), limits()))
        .isInstanceOf(SidecarProtocolException.class)
        .hasMessageContaining("malformed JSON");
  }

  @Test
  void timeout_kills_the_worker_process(@TempDir Path temporary) throws Exception {
    Path script = script(temporary, "import time\ntime.sleep(2)\n");
    PythonSidecarWorkerPool workers = SidecarTestFixtures.workers(script);
    SidecarLimits shortLimit =
        new SidecarLimits(10, 1, Duration.ofMillis(100), 1_024);

    assertThatThrownBy(
            () ->
                workers.execute(
                    "timeout", "sympy_simplify", object(), shortLimit))
        .isInstanceOf(SidecarProtocolException.class)
        .hasMessageContaining("timed out");
  }

  @Test
  void crash_is_reported_as_a_bounded_protocol_failure(@TempDir Path temporary)
      throws Exception {
    Path script = script(temporary, "raise SystemExit(7)\n");
    PythonSidecarWorkerPool workers = SidecarTestFixtures.workers(script);

    assertThatThrownBy(
            () ->
                workers.execute("crash", "sympy_simplify", object(), limits()))
        .isInstanceOf(SidecarProtocolException.class)
        .hasMessageContaining("exited with code 7");
  }

  @Test
  void oversized_stdout_is_rejected_while_the_pipe_is_drained(@TempDir Path temporary)
      throws Exception {
    Path script = script(temporary, "print('x' * 10000)\n");
    PythonSidecarWorkerPool workers = SidecarTestFixtures.workers(script);

    assertThatThrownBy(
            () ->
                workers.execute(
                    "oversize",
                    "sympy_simplify",
                    object(),
                    new SidecarLimits(
                        10, 1, Duration.ofSeconds(5), 256)))
        .isInstanceOf(SidecarProtocolException.class)
        .hasMessageContaining("output bound");
  }

  @Test
  void sandbox_ast_validator_rejects_import_file_and_attribute_access() {
    PythonSandboxAstValidator validator =
        new PythonSandboxAstValidator(
            SidecarTestFixtures.python(),
            SidecarTestFixtures.astValidator());
    assertThatThrownBy(
            () ->
                validator.validate(
                    "import subprocess\ndef run(data):\n"
                        + "    return subprocess.run(['whoami'])\n",
                    Set.of("subprocess")))
        .isInstanceOf(UnsafeProgramError.class);
    assertThatThrownBy(
            () ->
                validator.validate(
                    "def run(data):\n    return open('secret').read()\n",
                    Set.of()))
        .isInstanceOf(UnsafeProgramError.class);
    assertThat(
            validator.validate(
                "from math import sqrt\ndef run(data):\n"
                    + "    return {'value': sqrt(data['x'])}\n",
                Set.of("math")))
        .containsExactly("math");
  }

  private static SidecarLimits limits() {
    return new SidecarLimits(1_000, 20260719, Duration.ofSeconds(5), 100_000);
  }

  private static ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static Path script(Path directory, String source) throws Exception {
    Path path = directory.resolve("fake-sidecar.py");
    Files.writeString(path, source, StandardCharsets.UTF_8);
    return path;
  }
}
