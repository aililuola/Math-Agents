package io.github.aililuola.mathproofmesh.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class SecretValueSecurityTest {
  private static final String SECRET = "phase03-super-secret-value";
  private static final StrictYamlConfigLoader LOADER = new StrictYamlConfigLoader();
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void secretIsRedactedFromToStringLogsJsonAndSnapshots() throws Exception {
    SystemConfig config =
        LOADER.read(
            "agents:\n"
                + "  - id: secure-agent\n"
                + "    provider: openai_compatible\n"
                + "    model: secure-model\n"
                + "    api_key: "
                + SECRET
                + "\n");
    AgentConfig agent = config.agents().getFirst();

    assertFalse(agent.toString().contains(SECRET));
    assertTrue(agent.toString().contains(SecretValue.REDACTED));
    assertFalse(JSON.writeValueAsString(agent).contains(SECRET));
    assertFalse(LOADER.normalizedTree(config).toString().contains(SECRET));
    assertFalse(LOADER.redactedTree(config).toString().contains(SECRET));
    assertEquals("\"[REDACTED]\"", JSON.writeValueAsString(agent.apiKey()));
  }

  @Test
  void secretNeverAppearsInValidationExceptionOrStackTrace() {
    String yaml =
        "agents:\n"
            + "  - id: secure-agent\n"
            + "    provider: openai_compatible\n"
            + "    model: secure-model\n"
            + "    api_key: "
            + SECRET
            + "\n"
            + "    unknown_field: true\n";

    ConfigValidationException failure =
        assertThrows(ConfigValidationException.class, () -> LOADER.read(yaml));
    StringWriter rendered = new StringWriter();
    failure.printStackTrace(new PrintWriter(rendered));

    assertFalse(failure.toString().contains(SECRET));
    assertFalse(rendered.toString().contains(SECRET));
  }

  @Test
  void environmentSecretIsResolvedOnlyOnDemandAndCanBeDestroyed() {
    SystemConfig config =
        LOADER.read(
            "agents:\n"
                + "  - id: env-agent\n"
                + "    provider: openai_compatible\n"
                + "    model: secure-model\n"
                + "    api_key_env: ENV_AGENT_KEY\n");
    AgentConfig agent = config.agents().getFirst();

    try (SecretValue resolved =
        agent.resolveKey(name -> "ENV_AGENT_KEY".equals(name) ? SECRET : null)) {
      assertEquals(SECRET, resolved.use(String::new));
      assertFalse(resolved.toString().contains(SECRET));
    }
    SecretValue destroyed =
        agent.resolveKey(name -> "ENV_AGENT_KEY".equals(name) ? SECRET : null);
    destroyed.close();
    assertTrue(destroyed.isDestroyed());
    assertThrows(IllegalStateException.class, () -> destroyed.use(String::new));
  }

  @Test
  void missingEnvironmentVariableErrorContainsNoSecretMaterial() {
    SystemConfig config =
        LOADER.read(
            "agents:\n"
                + "  - id: env-agent\n"
                + "    provider: openai_compatible\n"
                + "    model: secure-model\n"
                + "    api_key_env: ENV_AGENT_KEY\n");

    ConfigValidationException failure =
        assertThrows(
            ConfigValidationException.class,
            () -> config.agents().getFirst().resolveKey(name -> null));

    assertEquals(
        "missing API key environment variable for agent 'env-agent': ENV_AGENT_KEY",
        failure.getMessage());
  }
}
