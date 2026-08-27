package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Phase17ComputationHardeningTest {

  @ParameterizedTest
  @MethodSource("numberTheoryOutcomes")
  void numberTheoryOperationsCoverCertifiedRefutedAndGuardedPaths(
      String arguments, ExperimentOutcome expected) {
    HandlerEvidence evidence =
        NumberTheoryFunctions.run(spec(ComputationMethod.NUMBER_THEORY_CHECK, arguments));

    assertThat(evidence.outcome()).isEqualTo(expected);
    assertThat(evidence.verificationNotes()).isNotEmpty();
  }

  private static Stream<Arguments> numberTheoryOutcomes() {
    return Stream.of(
        Arguments.of(
            "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":5}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":5,\"claimed\":3}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"multiplicative_order\",\"a\":2,"
                + "\"n\":1000000000001}",
            ExperimentOutcome.INCONCLUSIVE),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[2,3],\"moduli\":[3,5]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[2,3],\"moduli\":[3,5],"
                + "\"claimed\":4}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[0,1],\"moduli\":[2,2]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[1],"
                + "\"moduli\":[1000000000001]}",
            ExperimentOutcome.INCONCLUSIVE),
        Arguments.of(
            "{\"operation\":\"p_adic_valuation\",\"p\":2,"
                + "\"expression\":\"x\",\"assignment\":{\"x\":8},\"claimed\":3}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"p_adic_valuation\",\"p\":2,"
                + "\"expression\":\"x\",\"assignment\":{\"x\":8},\"claimed\":2}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"p_adic_valuation\",\"p\":2,"
                + "\"expression\":\"x-x\",\"assignment\":{\"x\":8}}",
            ExperimentOutcome.INCONCLUSIVE),
        Arguments.of(
            "{\"operation\":\"p_adic_valuation\",\"p\":1000000000000000001,"
                + "\"expression\":\"1\"}",
            ExperimentOutcome.INCONCLUSIVE),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":7,\"claimed\":3}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":7,\"claimed\":2}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":8}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":8,\"claimed\":true}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":7,\"claimed\":false}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":1000000000001}",
            ExperimentOutcome.INCONCLUSIVE),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":2,\"claimed\":true}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":37}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":1}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":25,\"claimed\":true}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":561}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":1000000000000000001}",
            ExperimentOutcome.INCONCLUSIVE),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":360}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":97,"
                + "\"claimed\":{\"97\":1}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":360,"
                + "\"claimed\":{\"2\":2,\"3\":2,\"5\":1}}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":1000000000000}",
            ExperimentOutcome.INCONCLUSIVE));
  }

  @ParameterizedTest
  @MethodSource("invalidNumberTheoryRequests")
  void numberTheoryRejectsMalformedOrUnsafeRequests(String arguments) {
    assertThatThrownBy(
            () ->
                NumberTheoryFunctions.run(
                    spec(ComputationMethod.NUMBER_THEORY_CHECK, arguments)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Stream<String> invalidNumberTheoryRequests() {
    String tooMany =
        "{\"operation\":\"crt\",\"residues\":["
            + "1,".repeat(64)
            + "1],\"moduli\":["
            + "2,".repeat(64)
            + "2]}";
    return Stream.of(
        "{}",
        "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":1}",
        "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":4}",
        "{\"operation\":\"crt\",\"residues\":[],\"moduli\":[]}",
        "{\"operation\":\"crt\",\"residues\":[1],\"moduli\":[2,3]}",
        "{\"operation\":\"crt\",\"residues\":[1],\"moduli\":[0]}",
        tooMany,
        "{\"operation\":\"p_adic_valuation\",\"p\":4,\"expression\":\"1\"}",
        "{\"operation\":\"p_adic_valuation\",\"p\":2,"
            + "\"expression\":\"x\",\"assignment\":{}}",
        "{\"operation\":\"primitive_root\",\"n\":1}",
        "{\"operation\":\"is_prime\",\"n\":7,\"claimed\":7}",
        "{\"operation\":\"factorization\",\"n\":1}",
        "{\"operation\":\"factorization\",\"n\":12,"
            + "\"claimed\":{\"not-a-prime\":1}}");
  }

  @ParameterizedTest
  @MethodSource("geometryOutcomes")
  void exactGeometryCoversEverySupportedAssertion(
      String assertion, ExperimentOutcome expected) {
    String points =
        """
        {
          "A":[0,0],"B":[1,0],"C":[2,0],"D":[0,1],
          "E":[1,1],"F":[2,1]
        }
        """;
    HandlerEvidence evidence =
        GeometryFunctions.run(
            spec(
                ComputationMethod.EXACT_GEOMETRY,
                "{\"points\":" + points + ",\"assertion\":" + assertion + "}"));

    assertThat(evidence.outcome()).isEqualTo(expected);
  }

  private static Stream<Arguments> geometryOutcomes() {
    return Stream.of(
        Arguments.of(
            "{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"C\"]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"C\"],"
                + "\"expected\":false}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"kind\":\"orientation\",\"points\":[\"A\",\"B\",\"D\"],"
                + "\"expected_sign\":1}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"kind\":\"equal_distance\",\"points\":[\"A\",\"B\",\"D\",\"E\"]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"kind\":\"point_on_segment\",\"points\":[\"B\",\"A\",\"C\"]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"kind\":\"point_on_segment\",\"points\":[\"D\",\"A\",\"C\"]}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"kind\":\"concyclic\",\"points\":[\"A\",\"B\",\"E\",\"D\"]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"kind\":\"parallel\",\"points\":[\"A\",\"B\",\"D\",\"E\"]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"kind\":\"perpendicular\",\"points\":[\"A\",\"B\",\"A\",\"D\"]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"kind\":\"equal_angle\","
                + "\"points\":[\"D\",\"A\",\"B\",\"E\",\"B\",\"C\"]}",
            ExperimentOutcome.CERTIFIED));
  }

  @ParameterizedTest
  @MethodSource("invalidGeometryRequests")
  void exactGeometryRejectsMalformedAssertions(String arguments) {
    assertThatThrownBy(
            () ->
                GeometryFunctions.run(
                    spec(ComputationMethod.EXACT_GEOMETRY, arguments)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Stream<String> invalidGeometryRequests() {
    return Stream.of(
        "{\"points\":{\"A\":[0,0]},\"assertion\":"
            + "{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"C\"]}}",
        "{\"points\":{\"A\":[0]},\"assertion\":{\"kind\":\"unknown\"}}",
        "{\"points\":{\"A\":[0,0]},\"assertion\":{\"kind\":\"unknown\"}}",
        "{\"points\":{\"A\":[0,0],\"B\":[1,0]},\"assertion\":"
            + "{\"kind\":\"collinear\",\"points\":[\"A\",\"B\"]}}",
        "{\"points\":{\"A\":[0,0],\"B\":[1,0],\"D\":[0,1]},"
            + "\"assertion\":{\"kind\":\"orientation\","
            + "\"points\":[\"A\",\"B\",\"D\"],\"expected_sign\":2}}",
        "{\"points\":{\"A\":[0,0],\"B\":[1,0],\"C\":[2,0]},"
            + "\"assertion\":{\"kind\":\"collinear\","
            + "\"points\":[\"A\",\"B\",\"C\"],\"expected\":\"yes\"}}",
        "{\"points\":{\"A\":[0,0],\"B\":[0,0],\"C\":[1,0],\"D\":[2,0]},"
            + "\"assertion\":{\"kind\":\"parallel\","
            + "\"points\":[\"A\",\"B\",\"C\",\"D\"]}}");
  }

  @ParameterizedTest
  @MethodSource("graphOutcomes")
  void graphCertificatesCoverEveryProperty(
      String arguments, ExperimentOutcome expected) {
    HandlerEvidence evidence =
        GraphFunctions.run(spec(ComputationMethod.GRAPH_CERTIFICATE, arguments));

    assertThat(evidence.outcome()).isEqualTo(expected);
  }

  private static Stream<Arguments> graphOutcomes() {
    String triangle =
        "\"graph\":{\"nodes\":[\"a\",\"b\",\"c\"],"
            + "\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"],[\"c\",\"a\"]]}";
    return Stream.of(
        Arguments.of(
            "{" + triangle + ",\"property\":\"proper_coloring\","
                + "\"certificate\":{\"colors\":{\"a\":0,\"b\":1,\"c\":2}}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{" + triangle + ",\"property\":\"proper_coloring\","
                + "\"certificate\":{\"colors\":{\"a\":0,\"b\":0,\"c\":1}}}",
            ExperimentOutcome.INCONCLUSIVE),
        Arguments.of(
            "{" + triangle + ",\"property\":\"path\","
                + "\"certificate\":{\"vertices\":[\"a\",\"b\",\"c\"]}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{" + triangle + ",\"property\":\"path\","
                + "\"certificate\":{\"vertices\":[\"a\",\"a\"]}}",
            ExperimentOutcome.INCONCLUSIVE),
        Arguments.of(
            "{" + triangle + ",\"property\":\"cycle\","
                + "\"certificate\":{\"vertices\":[\"a\",\"b\",\"c\"]}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{" + triangle + ",\"property\":\"matching\","
                + "\"certificate\":{\"edges\":[[\"a\",\"b\"]]}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{" + triangle + ",\"property\":\"matching\","
                + "\"certificate\":{\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"]]}}",
            ExperimentOutcome.INCONCLUSIVE),
        Arguments.of(
            "{" + triangle + ",\"property\":\"connected\",\"certificate\":{}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"graph\":{\"nodes\":[\"a\",\"b\",\"c\"],"
                + "\"edges\":[[\"a\",\"b\"]]},\"property\":\"connected\","
                + "\"certificate\":{}}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"graph\":{\"nodes\":[\"a\",\"b\",\"c\"],\"directed\":true,"
                + "\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"],[\"c\",\"a\"]]},"
                + "\"property\":\"connected\",\"certificate\":{}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"graph\":{\"nodes\":[\"a\",\"b\",\"c\"],\"directed\":true,"
                + "\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"]]},"
                + "\"property\":\"connected\",\"certificate\":{}}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND));
  }

  @ParameterizedTest
  @MethodSource("invalidGraphRequests")
  void graphCertificatesRejectMalformedGraphs(String arguments) {
    assertThatThrownBy(
            () ->
                GraphFunctions.run(
                    spec(ComputationMethod.GRAPH_CERTIFICATE, arguments)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Stream<String> invalidGraphRequests() {
    return Stream.of(
        "{\"graph\":{\"nodes\":[\"a\",\"a\"],\"edges\":[]},"
            + "\"property\":\"connected\",\"certificate\":{}}",
        "{\"graph\":{\"nodes\":[\"a\"],\"edges\":[[\"a\",\"b\"]]},"
            + "\"property\":\"connected\",\"certificate\":{}}",
        "{\"graph\":{\"nodes\":[\"a\"],\"edges\":[]},"
            + "\"property\":\"unknown\",\"certificate\":{}}",
        "{\"graph\":{\"nodes\":[{}],\"edges\":[]},"
            + "\"property\":\"connected\",\"certificate\":{}}",
        "{\"graph\":{\"nodes\":[\"a\"],\"edges\":[[\"a\"]]},"
            + "\"property\":\"connected\",\"certificate\":{}}",
        "{\"graph\":{\"nodes\":[\"a\",\"b\"],\"directed\":true,"
            + "\"edges\":[[\"a\",\"b\"]]},\"property\":\"matching\","
            + "\"certificate\":{\"edges\":[]}}");
  }

  @Test
  void sandboxSchemaValidationCoversNestedTypesAndFailures(@TempDir Path temporary) {
    ObjectNode input =
        object(
            """
            {
              "type":"object",
              "properties":{
                "seed":{"type":"integer"},
                "mode":{"type":"string","enum":["fast","exact"]},
                "values":{"type":"array","items":{"type":"number"}},
                "flag":{"type":"boolean"},
                "none":{"type":"null"},
                "meta":{
                  "type":"object",
                  "properties":{"id":{"const":"x"}},
                  "required":[]
                }
              },
              "required":["seed"],
              "additionalProperties":false
            }
            """);
    ObjectNode output =
        object(
            """
            {
              "type":"object",
              "properties":{
                "outcome":{"type":"string"},
                "cases_checked":{"type":"integer"},
                "scope":{"type":"object"},
                "exact_arithmetic":{"type":"boolean"}
              },
              "required":["outcome","cases_checked","scope","exact_arithmetic"]
            }
            """);
    ExperimentProgram program =
        new ExperimentProgram(
            "",
            "2026-07-31T00:00:00Z",
            List.of(),
            "phase17",
            input,
            output,
            "def run(data): return data");

    SandboxFunctions.validateProgramSchemas(program);
    ObjectNode validated =
        SandboxFunctions.validateJsonObject(
            object(
                "{\"seed\":7,\"mode\":\"fast\",\"values\":[1,2.5],"
                    + "\"flag\":true,\"none\":null,\"meta\":{\"id\":\"x\"}}"),
            input,
            "input");
    assertThat(validated.path("seed").intValue()).isEqualTo(7);

    assertThatThrownBy(
            () ->
                SandboxFunctions.validateJsonObject(
                    object("{\"mode\":\"slow\"}"), input, "input"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SandboxFunctions.validateJsonObject(
                    object("{\"seed\":1,\"extra\":true}"), input, "input"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SandboxFunctions.validateJsonObject(
                    object("{\"seed\":\"bad\"}"), input, "input"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SandboxFunctions.validateJsonObject(
                    object("{\"seed\":1,\"meta\":{\"id\":\"wrong\"}}"),
                    input,
                    "input"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SandboxFunctions.validateJsonObject(
                    object("{\"seed\":1}"),
                    object("{\"type\":\"unsupported\"}"),
                    "input"))
        .isInstanceOf(IllegalArgumentException.class);

    SandboxSettings enabled =
        new SandboxSettings(
            true,
            "python@sha256:" + "a".repeat(64),
            Duration.ofSeconds(5),
            128,
            0.5,
            16,
            1000);
    List<String> command =
        SandboxFunctions.buildDockerCommand("", enabled, temporary);
    assertThat(command).contains("docker", "--network", "none", "--read-only");
    assertThatThrownBy(
            () ->
                SandboxFunctions.buildDockerCommand(
                    "docker", SandboxSettings.disabled(), temporary))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                SandboxFunctions.buildDockerCommand(
                    "docker", enabled, temporary.resolve("missing")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("badProgramSchemas")
  void sandboxProgramSchemaFailuresAreFailClosed(String inputJson, String outputJson) {
    ExperimentProgram program =
        new ExperimentProgram(
            "",
            "2026-07-31T00:00:00Z",
            List.of(),
            "bad-phase17",
            object(inputJson),
            object(outputJson),
            "def run(data): return data");

    assertThatThrownBy(() -> SandboxFunctions.validateProgramSchemas(program))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Stream<Arguments> badProgramSchemas() {
    String validInput =
        "{\"properties\":{\"seed\":{\"type\":\"integer\"}},"
            + "\"required\":[\"seed\"]}";
    String validOutput =
        "{\"properties\":{\"outcome\":{\"type\":\"string\"},"
            + "\"cases_checked\":{\"type\":\"integer\"},"
            + "\"scope\":{\"type\":\"object\"},"
            + "\"exact_arithmetic\":{\"type\":\"boolean\"}},"
            + "\"required\":[\"outcome\",\"cases_checked\","
            + "\"scope\",\"exact_arithmetic\"]}";
    return Stream.of(
        Arguments.of("{}", validOutput),
        Arguments.of(
            "{\"properties\":{\"seed\":{\"type\":\"string\"}},"
                + "\"required\":[\"seed\"]}",
            validOutput),
        Arguments.of(validInput, "{}"),
        Arguments.of(
            validInput,
            "{\"properties\":{},\"required\":[\"outcome\"]}"));
  }

  private static ExperimentSpec spec(ComputationMethod method, String arguments) {
    return ComputationFixtures.spec(method, arguments);
  }

  private static ObjectNode object(String json) {
    return ComputationFixtures.object(json);
  }
}
