package io.github.aililuola.mathproofmesh.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StrictYamlConfigLoaderTest {
  private static final StrictYamlConfigLoader LOADER = new StrictYamlConfigLoader();

  @Test
  void appliesPythonDefaultsWithoutSpringRelaxedBinding() {
    SystemConfig config = LOADER.read(minimal());

    assertEquals("MathProofMesh", config.systemName());
    assertEquals(48, config.budget().maxTotalCalls());
    assertEquals("disabled", config.runtime().stageThinkingModes().get("goal_normalization"));
  }

  @Test
  void rejectsUnknownFieldsAtEveryLevel() {
    String yaml = minimal() + "runtime:\n  unexpected_field: true\n";
    ConfigValidationException failure =
        assertThrows(ConfigValidationException.class, () -> LOADER.read(yaml));

    assertTrue(failure.getMessage().contains("unexpected_field"));
  }

  @Test
  void rejectsDuplicateKeys() {
    String yaml =
        "agents:\n"
            + "  - id: first\n"
            + "    id: second\n"
            + "    provider: mock\n"
            + "    model: mock\n";

    assertThrows(ConfigValidationException.class, () -> LOADER.read(yaml));
  }

  @Test
  void rejectsScalarCoercion() {
    String yaml =
        "agents:\n"
            + "  - id: mock\n"
            + "    provider: mock\n"
            + "    model: mock\n"
            + "    max_concurrency: \"3\"\n";

    assertThrows(ConfigValidationException.class, () -> LOADER.read(yaml));
  }

  @Test
  void rejectsExplicitNullForNonNullableFields() {
    String yaml =
        "agents:\n"
            + "  - id: mock\n"
            + "    provider: mock\n"
            + "    model: mock\n"
            + "    enabled: null\n";

    assertThrows(ConfigValidationException.class, () -> LOADER.read(yaml));
  }

  @Test
  void rejectsMissingRequiredFieldsAndNonMappingRoot() {
    assertThrows(
        ConfigValidationException.class,
        () -> LOADER.read("agents:\n  - provider: mock\n    model: mock\n"));
    assertThrows(ConfigValidationException.class, () -> LOADER.read("- item\n"));
  }

  @Test
  void preservesTheThreeExplicitPythonLegacyMappings() {
    String yaml =
        minimal()
            + "runtime:\n"
            + "  reasoning_only_abort_seconds: 1\n"
            + "  reasoning_only_min_characters: 2\n"
            + "deep_exploration_policy:\n"
            + "  tiers:\n"
            + "    - output_tokens: 64000\n"
            + "      answer_reserve_tokens: 8000\n"
            + "      no_content_timeout_seconds: 3\n"
            + "      wall_timeout_seconds: 4\n";

    SystemConfig config = LOADER.read(yaml);

    assertEquals(8000, config.deepExplorationPolicy().tiers().getFirst().artifactRecoveryTokens());
  }

  private static String minimal() {
    return "agents:\n"
        + "  - id: mock-agent\n"
        + "    provider: mock\n"
        + "    model: mock-model\n";
  }
}
