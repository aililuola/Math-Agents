package io.github.aililuola.mathproofmesh.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigProfileParityTest {
  private static final Path ROOT =
      Path.of(System.getProperty("mathproofmesh.projectRoot"));
  private static final StrictYamlConfigLoader LOADER = new StrictYamlConfigLoader();
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest(name = "{0}")
  @MethodSource("profiles")
  void originalProfilesMatchPythonNormalizedSemantics(
      String profile, String expectedFixture) throws IOException {
    SystemConfig config = LOADER.load(ROOT.resolve("config").resolve(profile));
    JsonNode expected =
        JSON.readTree(
            ROOT.resolve("migration/baseline/config-fixtures/normalized")
                .resolve(expectedFixture)
                .toFile());

    assertEquals(expected, LOADER.normalizedTree(config));
    assertFalse(LOADER.normalizedTree(config).toString().contains("\"api_key\":"));
  }

  @ParameterizedTest(name = "redacted {0}")
  @MethodSource("profiles")
  void redactedProfilesMatchPythonRedactedDictionary(
      String profile, String expectedFixture) throws IOException {
    SystemConfig config = LOADER.load(ROOT.resolve("config").resolve(profile));
    ObjectNode expected =
        (ObjectNode)
            JSON.readTree(
                ROOT.resolve("migration/baseline/config-fixtures/normalized")
                    .resolve(expectedFixture)
                    .toFile());
    ArrayNode agents = (ArrayNode) expected.path("agents");
    for (JsonNode item : agents) {
      ObjectNode agent = (ObjectNode) item;
      agent.put(
          "key_status",
          agent.path("api_key_env").isTextual()
              ? "configured-via-env"
              : "inline-secret-redacted");
    }

    assertEquals(expected, LOADER.redactedTree(config));
  }

  static Stream<Arguments> profiles() {
    return Stream.of(
        Arguments.of(
            "proof-control-active.yaml",
            "config.deepseek-v4-pro.proof-control-active.json"),
        Arguments.of(
            "proof-control-shadow.yaml",
            "config.deepseek-v4-pro.proof-control-shadow.json"),
        Arguments.of(
            "deepseek-v4-pro-smoke.yaml",
            "config.deepseek-v4-pro.smoke.json"),
        Arguments.of(
            "topology-active.yaml",
            "config.deepseek-v4-pro.topology-active.json"),
        Arguments.of(
            "deepseek-v4-pro.yaml",
            "config.deepseek-v4-pro.json"),
        Arguments.of("application.yaml", "config.example.json"));
  }
}
