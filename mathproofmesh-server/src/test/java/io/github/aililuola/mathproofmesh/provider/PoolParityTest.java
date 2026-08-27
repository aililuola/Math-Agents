package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.config.StrictYamlConfigLoader;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PoolParityTest {
  @Test
  void agentSelectionSequenceIsReproducible() {
    SystemConfig config = config();
    try (AgentPool first = pool(config);
        AgentPool second = pool(config)) {
      List<String> firstSequence =
          java.util.stream.IntStream.range(0, 8)
              .mapToObj(
                  ignored ->
                      first
                          .select(
                              "explorer",
                              Set.of(),
                              List.of(),
                              null,
                              false)
                          .id())
              .toList();
      List<String> secondSequence =
          java.util.stream.IntStream.range(0, 8)
              .mapToObj(
                  ignored ->
                      second
                          .select(
                              "explorer",
                              Set.of(),
                              List.of(),
                              null,
                              false)
                          .id())
              .toList();

      assertThat(firstSequence).isEqualTo(secondSequence);
    }
  }

  @Test
  void strictExclusionNeverReusesAnExcludedAgent() {
    SystemConfig config = config();
    try (AgentPool pool = pool(config)) {
      Set<String> all =
          pool.agents().stream()
              .map(AgentRuntime::id)
              .collect(java.util.stream.Collectors.toUnmodifiableSet());

      assertThatThrownBy(
              () ->
                  pool.select(
                      "final_verifier",
                      all,
                      List.of(),
                      null,
                      true))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("strict author exclusion");
    }
  }

  @Test
  void streamRetryUsesPublicPrefixButNotPrivateReasoning() {
    ProviderException disconnect =
        ProviderException.network(
            new IOException("stream disconnected"),
            "{\"answer\":\"partial",
            "private reasoning".length());
    ProviderRequest original =
        ProviderRequest.json(
            List.of(new ChatMessage("user", "Solve the task.")),
            1024,
            true);

    ProviderRequest retried =
        AgentRuntime.retryRequest(original, disconnect);

    assertThat(retried.messages().getFirst()).isEqualTo(original.messages().getFirst());
    assertThat(retried.messages().getLast().content())
        .contains(
            "PUBLIC_OUTPUT_PREFIX",
            "{\"answer\":\"partial",
            disconnect.partialPublicContentSha256())
        .doesNotContain("private reasoning");
  }

  private static SystemConfig config() {
    return new StrictYamlConfigLoader()
        .read(
            """
            agents:
              - id: explorer-a
                provider: mock
                model: mock-a
                roles: [explorer, final_verifier]
              - id: explorer-b
                provider: mock
                model: mock-b
                roles: [explorer, final_verifier]
              - id: explorer-c
                provider: mock
                model: mock-c
                roles: [explorer, final_verifier]
            """);
  }

  private static AgentPool pool(SystemConfig config) {
    Map<String, MockResponder> responders =
        Map.of(
            "explorer-a", ignored -> response(),
            "explorer-b", ignored -> response(),
            "explorer-c", ignored -> response());
    ProviderClientRegistry registry =
        new ProviderClientRegistry(
            config,
            responders,
            ignored ->
                request -> {
                  throw new AssertionError("mock provider must not use HTTP");
                },
            false);
    return new AgentPool(config, registry);
  }

  private static LLMResponse response() {
    return new LLMResponse(
        "{}",
        "mock",
        "mock",
        0,
        0,
        0.0d,
        null,
        "stop",
        false,
        null);
  }
}
