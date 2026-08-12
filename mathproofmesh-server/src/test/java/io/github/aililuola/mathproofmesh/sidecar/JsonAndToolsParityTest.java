package io.github.aililuola.mathproofmesh.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.agent.JsonObjectExtractor;
import io.github.aililuola.mathproofmesh.computation.ExactExpression;
import io.github.aililuola.mathproofmesh.computation.ToolBroker;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import io.github.aililuola.mathproofmesh.contract.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonAndToolsParityTest {

  @Test
  void test_balanced_json_extraction_tolerates_prose_and_braces_in_strings() {
    String extracted =
        JsonObjectExtractor.firstBalancedObject(
            "analysis first\n{\"text\":\"a } brace\","
                + "\"nested\":{\"value\":2}} trailing");
    assertThat(extracted)
        .isEqualTo("{\"text\":\"a } brace\",\"nested\":{\"value\":2}}");
  }

  @Test
  void test_safe_expression_parser_rejects_arbitrary_python() {
    assertThatThrownBy(
            () ->
                ExactExpression.parse(
                    "__import__('os').system('whoami')"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ExactExpression.parse("x^2+2*x+1").variables())
        .containsExactly("x");
  }

  @Test
  void test_tool_broker_equivalence_and_counterexample() {
    ToolBroker tools = new ToolBroker(SidecarTestFixtures.broker("sidecar-tools"));
    List<ToolResult> results =
        tools.executeMany(
            List.of(
                request(
                    "sympy_equivalent",
                    "{\"lhs\":\"x+1\",\"rhs\":\"1+x\"}"),
                request(
                    "numeric_counterexample",
                    "{\"lhs\":\"x^2\",\"rhs\":\"x\","
                        + "\"relation\":\"eq\",\"variables\":[\"x\"],"
                        + "\"ranges\":{\"x\":[2,2]},\"samples\":1}")));

    assertThat(results).allMatch(ToolResult::ok);
    assertThat(results.get(0).result().path("equivalent").asBoolean()).isTrue();
    assertThat(results.get(1).result().path("counterexample_found").asBoolean())
        .isTrue();
    assertThat(
            results
                .get(1)
                .result()
                .path("independently_verified")
                .asBoolean())
        .isTrue();
  }

  private static ToolRequest request(String kind, String arguments) {
    return new ToolRequest(
        (com.fasterxml.jackson.databind.node.ObjectNode)
            io.github.aililuola.mathproofmesh.contract.ContractObjectMapper
                .parseTree(arguments),
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
        kind,
        1_000,
        "Check the declared exact mathematical relation.",
        null);
  }
}
