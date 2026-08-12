package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.api.ReasoningTraceBinding;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import io.github.aililuola.mathproofmesh.config.ConfigValidationException;
import io.github.aililuola.mathproofmesh.config.SecretValue;
import io.github.aililuola.mathproofmesh.config.StrictYamlConfigLoader;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeepseekParityTest {
  private static final URI BASE = URI.create("https://203.0.113.1/v1");
  private static final ProviderLimits LIMITS =
      new ProviderLimits(
          128 * 1024, Duration.ofSeconds(1), Duration.ofSeconds(1));

  @Test
  void deepseekAgentConfigRequiresThinkingForEffort() {
    assertThatThrownBy(
            () ->
                new StrictYamlConfigLoader()
                    .read(
                        """
                        agents:
                          - id: bad
                            provider: deepseek
                            model: deepseek-v4-pro
                            api_key: fixture
                            thinking_enabled: false
                            reasoning_effort: max
                        """))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("reasoning_effort requires thinking_enabled");
  }

  @Test
  void deepseekAgentConfigAcceptsV4ProMax() {
    SystemConfig config =
        new StrictYamlConfigLoader()
            .read(
                """
                agents:
                  - id: agent-1
                    provider: deepseek
                    model: deepseek-v4-pro
                    api_key: fixture
                    thinking_enabled: true
                    reasoning_effort: max
                    streaming: true
                    user_id: mathproofmesh-agent-1
                """);

    assertThat(config.agents().getFirst().reasoningEffort()).isEqualTo("max");
    assertThat(config.agents().getFirst().thinkingEnabled()).isTrue();
    assertThat(config.agents().getFirst().streaming()).isTrue();
  }

  @Test
  void agentPoolConstructsDeepseekClientWithIsolatedPolicy() {
    SystemConfig config =
        new StrictYamlConfigLoader()
            .read(
                """
                agents:
                  - id: ds-agent
                    provider: deepseek
                    model: deepseek-v4-pro
                    api_key: fixture
                    thinking_enabled: true
                    reasoning_effort: max
                    streaming: true
                    user_id: isolated-agent
                """);
    QueueTransport transport =
        new QueueTransport(
            List.of(jsonResponse(successBody("{\"ok\":true}"))));
    ProviderClientRegistry registry =
        new ProviderClientRegistry(
            config, Map.of(), ignored -> transport, false);
    try (AgentPool pool = new AgentPool(config, registry)) {
      assertThat(registry.get("ds-agent"))
          .isInstanceOf(DeepSeekClient.class);
      assertThat(pool.get("ds-agent").config().reasoningEffort())
          .isEqualTo("max");
      assertThat(pool.get("ds-agent").config().thinkingEnabled()).isTrue();
      assertThat(pool.get("ds-agent").config().streaming()).isTrue();
      assertThat(pool.get("ds-agent").config().userId())
          .isEqualTo("isolated-agent");
    }
  }

  @Test
  void deepseekPayloadAndReasoningAreRedacted() {
    QueueTransport transport =
        new QueueTransport(
            List.of(
                jsonResponse(
                    """
                    {
                      "id":"chat-test",
                      "model":"deepseek-v4-pro",
                      "choices":[{
                        "finish_reason":"stop",
                        "message":{
                          "reasoning_content":"private intermediate reasoning",
                          "content":"{\\"ok\\":true}"
                        }
                      }],
                      "usage":{"prompt_tokens":11,"completion_tokens":13}
                    }
                    """)));
    try (DeepSeekClient client = client(transport)) {
      LLMResponse response = client.complete(policyRequest(true, "max", false));

      String body = transport.requestBody(0);
      assertThat(body)
          .contains(
              "\"model\":\"deepseek-v4-pro\"",
              "\"thinking\":{\"type\":\"enabled\"}",
              "\"reasoning_effort\":\"max\"",
              "\"user_id\":\"mathproofmesh-agent-1\"",
              "\"response_format\":{\"type\":\"json_object\"}",
              "\"stream\":false")
          .doesNotContain("\"temperature\"");
      assertThat(response.text()).isEqualTo("{\"ok\":true}");
      assertThat(response.provider()).isEqualTo("deepseek");
      assertThat(response.metadata().toString())
          .contains("\"present\":true")
          .doesNotContain("private intermediate reasoning")
          .doesNotContain("reasoning_content");
    }
  }

  @Test
  void perCallPolicyCanDisableOrLowerThinking() {
    QueueTransport transport =
        new QueueTransport(
            List.of(
                jsonResponse(successBody("{}")),
                jsonResponse(successBody("{}"))));
    try (DeepSeekClient client = client(transport)) {
      client.complete(policyRequest(false, null, false));
      client.complete(policyRequest(true, "high", false));
    }

    assertThat(transport.requestBody(0))
        .contains(
            "\"thinking\":{\"type\":\"disabled\"}",
            "\"temperature\":0.2")
        .doesNotContain("reasoning_effort");
    assertThat(transport.requestBody(1))
        .contains(
            "\"thinking\":{\"type\":\"enabled\"}",
            "\"reasoning_effort\":\"high\"")
        .doesNotContain("\"temperature\"");
  }

  @Test
  void streamingProgressIsScopedToOneRequestObject() {
    QueueTransport transport =
        new QueueTransport(
            List.of(
                sseResponse(streamBody("one", true)),
                sseResponse(streamBody("two", true))));
    ProviderRequest first = policyRequest(true, "max", true);
    ProviderRequest second = policyRequest(true, "max", true);
    try (DeepSeekClient client = client(transport)) {
      client.complete(first);
      client.complete(second);

      assertThat(client.progressSnapshotFor(first))
          .containsEntry("status", "succeeded")
          .containsEntry("chunks", 3);
      assertThat(client.progressSnapshotFor(second))
          .containsEntry("status", "succeeded")
          .containsEntry("chunks", 3);
      client.clearProgressFor(first);
      assertThat(client.progressSnapshotFor(first)).isEmpty();
      assertThat(client.progressSnapshotFor(second)).isNotEmpty();
    }
  }

  @Test
  void streamingPayloadUsageAndReasoningRedactionMatchProtocol() {
    String body =
        """
        : keep-alive

        data: {"id":"chat-stream","model":"deepseek-v4-pro","choices":[{"delta":{"reasoning_content":"private "}}]}

        data: {"id":"chat-stream","model":"deepseek-v4-pro","choices":[{"delta":{"reasoning_content":[{"text":"reasoning"}]}}]}

        data: {"id":"chat-stream","model":"deepseek-v4-pro","choices":[{"delta":{"content":"{\\"ok\\":"}}]}

        data: {"id":"chat-stream","model":"deepseek-v4-pro","choices":[{"finish_reason":"stop","delta":{"content":"true}"}}]}

        data: {"id":"chat-stream","model":"deepseek-v4-pro","choices":[],"usage":{"prompt_tokens":17,"completion_tokens":19}}

        data: [DONE]

        """;
    QueueTransport transport =
        new QueueTransport(List.of(sseResponse(body)));
    try (DeepSeekClient client = client(transport)) {
      LLMResponse response = client.complete(policyRequest(true, "max", true));

      assertThat(transport.requestBody(0))
          .contains(
              "\"stream\":true",
              "\"stream_options\":{\"include_usage\":true}",
              "\"thinking\":{\"type\":\"enabled\"}")
          .doesNotContain("\"temperature\"");
      assertThat(response.text()).isEqualTo("{\"ok\":true}");
      assertThat(response.inputTokens()).isEqualTo(17);
      assertThat(response.outputTokens()).isEqualTo(19);
      assertThat(response.metadata().toString())
          .contains(
              "\"characters\":17",
              "\"chunks\":5",
              "\"done_received\":true",
              "\"first_chunk_latency_ms\"")
          .doesNotContain("private reasoning");
    }
  }

  @Test
  void streamingReasoningIsAppendedToTheBoundRunArchive(@TempDir Path directory) {
    String body =
        """
        data: {"choices":[{"delta":{"reasoning_content":"first "}}]}

        data: {"choices":[{"delta":{"reasoning_content":"second"}}]}

        data: {"choices":[{"finish_reason":"stop","delta":{"content":"{}"}}]}

        data: {"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3}}

        data: [DONE]

        """;
    ReasoningTraceStore store = new ReasoningTraceStore(directory, "trace-run");
    ReasoningTraceBinding binding =
        new ReasoningTraceBinding(store, "agent:explore:agent-1", "agent-1", "explore");
    try (DeepSeekClient client = client(new QueueTransport(List.of(sseResponse(body))))) {
      ReasoningTraceBinding.Scope scope = binding.bind();
      try {
        client.complete(policyRequest(true, "max", true));
      } finally {
        scope.close();
      }
    }

    Map<String, Object> snapshot =
        ReasoningTraceStore.buildSnapshot(
            ReasoningTraceStore.readRecords(store.path(), binding.taskId(), 0L).records());
    assertThat(snapshot.get("has_reasoning")).isEqualTo(true);
    assertThat(((Map<?, ?>) ((List<?>) snapshot.get("calls")).getFirst()).get("text"))
        .isEqualTo("first second");
  }

  @Test
  void incompleteSseIsRejected() {
    QueueTransport transport =
        new QueueTransport(
            List.of(
                sseResponse(
                    """
                    data: {"choices":[{"delta":{"content":"partial"}}]}

                    """)));
    try (DeepSeekClient client = client(transport)) {
      assertThatThrownBy(
              () -> client.complete(policyRequest(true, "max", true)))
          .isInstanceOf(ProviderException.class)
          .hasMessageContaining("ended before data: [DONE]");
    }
  }

  @Test
  void modelListProbeUsesModelsEndpoint() {
    QueueTransport transport =
        new QueueTransport(
            List.of(
                jsonResponse(
                    """
                    {
                      "object":"list",
                      "data":[
                        {"id":"deepseek-v4-flash"},
                        {"id":"deepseek-v4-pro"}
                      ]
                    }
                    """)));
    try (DeepSeekClient client = client(transport)) {
      assertThat(client.listModels())
          .containsExactly("deepseek-v4-flash", "deepseek-v4-pro");
      assertThat(transport.requests().getFirst().method()).isEqualTo("GET");
      assertThat(transport.requests().getFirst().uri().getPath())
          .isEqualTo("/v1/models");
    }
  }

  @Test
  void disconnectSalvagesOnlyPublicPrefixAndReasoningCount() {
    String partial =
        """
        data: {"choices":[{"delta":{"reasoning_content":"private A; ","content":"{\\"answer\\":\\"partial"}}]}

        data: {"choices":[{"delta":{"reasoning_content":"private B"}}]}

        """;
    QueueTransport transport =
        new QueueTransport(
            List.of(
                new ResponseFixture(
                    200,
                    Map.of("content-type", List.of("text/event-stream")),
                    () ->
                        new DisconnectingInputStream(
                            partial.getBytes(StandardCharsets.UTF_8)))));
    try (DeepSeekClient client = client(transport)) {
      assertThatThrownBy(
              () -> client.complete(policyRequest(true, "max", true)))
          .isInstanceOf(ProviderException.class)
          .satisfies(
              failure -> {
                ProviderException providerFailure =
                    (ProviderException) failure;
                assertThat(providerFailure.partialPublicContent())
                    .isEqualTo("{\"answer\":\"partial");
                assertThat(providerFailure.partialPublicContentSha256())
                    .hasSize(64);
                assertThat(providerFailure.partialReasoningCharacters())
                    .isEqualTo("private A; private B".length());
                assertThat(providerFailure.getMessage())
                    .doesNotContain("private A", "private B");
              });
    }
  }

  @Test
  void streamingRequiresRequestedUsageSummary() {
    QueueTransport transport =
        new QueueTransport(
            List.of(
                sseResponse(
                    """
                    data: {"choices":[{"finish_reason":"stop","delta":{"content":"{}"}}]}

                    data: [DONE]

                    """)));
    try (DeepSeekClient client = client(transport)) {
      assertThatThrownBy(
              () -> client.complete(policyRequest(true, "max", true)))
          .isInstanceOf(ProviderException.class)
          .hasMessageContaining("without requested usage");
    }
  }

  private static DeepSeekClient client(QueueTransport transport) {
    return new DeepSeekClient(
        SecretValue.of("fixture-key"),
        "deepseek-v4-pro",
        BASE,
        Duration.ofSeconds(2),
        Map.of(),
        transport,
        LIMITS,
        LiveProviderPolicy.disabled());
  }

  private static ProviderRequest policyRequest(
      boolean thinking, String effort, boolean streaming) {
    return new ProviderRequest(
        List.of(new ChatMessage("user", "Return JSON.")),
        0.2d,
        4096,
        true,
        null,
        null,
        thinking,
        effort,
        streaming,
        "mathproofmesh-agent-1",
        null);
  }

  private static String successBody(String content) {
    return """
        {
          "id":"chat-success",
          "model":"deepseek-v4-pro",
          "choices":[{"finish_reason":"stop","message":{"content":"%s"}}],
          "usage":{"prompt_tokens":2,"completion_tokens":3}
        }
        """
        .formatted(content.replace("\"", "\\\""));
  }

  private static String streamBody(String content, boolean usage) {
    return """
        data: {"choices":[{"delta":{"content":"%s"}}]}

        %s
        data: [DONE]

        """
        .formatted(
            content,
            usage
                ? "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}\n"
                : "");
  }

  private static ResponseFixture jsonResponse(String body) {
    return new ResponseFixture(
        200,
        Map.of("content-type", List.of("application/json")),
        () ->
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  private static ResponseFixture sseResponse(String body) {
    return new ResponseFixture(
        200,
        Map.of("content-type", List.of("text/event-stream")),
        () ->
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  private record ResponseFixture(
      int status,
      Map<String, List<String>> headers,
      Supplier<InputStream> body) {}

  private static final class QueueTransport implements HttpTransport {
    private final Deque<ResponseFixture> responses;
    private final List<HttpTransportRequest> requests = new ArrayList<>();

    private QueueTransport(List<ResponseFixture> responses) {
      this.responses = new ArrayDeque<>(responses);
    }

    @Override
    public HttpTransportResponse send(HttpTransportRequest request) {
      requests.add(request);
      ResponseFixture response = responses.removeFirst();
      return new HttpTransportResponse(
          response.status(), response.headers(), response.body().get());
    }

    @Override
    public boolean reachesNetwork() {
      return false;
    }

    String requestBody(int index) {
      return new String(
          requests.get(index).body(), StandardCharsets.UTF_8);
    }

    List<HttpTransportRequest> requests() {
      return List.copyOf(requests);
    }
  }

  private static final class DisconnectingInputStream extends InputStream {
    private final byte[] prefix;
    private int offset;

    private DisconnectingInputStream(byte[] prefix) {
      this.prefix = prefix.clone();
    }

    @Override
    public int read() throws IOException {
      if (offset < prefix.length) {
        return Byte.toUnsignedInt(prefix[offset++]);
      }
      throw new IOException("fixture disconnect");
    }

    @Override
    public int read(byte[] target, int targetOffset, int length)
        throws IOException {
      if (offset < prefix.length) {
        int count = Math.min(length, prefix.length - offset);
        System.arraycopy(prefix, offset, target, targetOffset, count);
        offset += count;
        return count;
      }
      throw new IOException("fixture disconnect");
    }
  }
}
