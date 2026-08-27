package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.config.SecretValue;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderAdapterFixtureTest {
  private static final URI BASE = URI.create("https://203.0.113.1/v1");
  private static final ProviderLimits LIMITS =
      new ProviderLimits(
          64 * 1024, Duration.ofSeconds(1), Duration.ofSeconds(1));

  @Test
  void openAiCompatibleMapsRequestResponseUsageAndRequestId() {
    FixtureTransport transport =
        FixtureTransport.json(
            200,
            """
            {
              "id":"body-id",
              "model":"fixture-gpt",
              "choices":[{
                "message":{
                  "content":"answer",
                  "reasoning_content":"private reasoning"
                },
                "finish_reason":"stop"
              }],
              "usage":{"prompt_tokens":3,"completion_tokens":2}
            }
            """,
            Map.of("x-request-id", List.of("header-id")));
    try (OpenAICompatibleClient client =
        new OpenAICompatibleClient(
            SecretValue.of("fixture-secret"),
            "fixture-gpt",
            BASE,
            Duration.ofSeconds(2),
            Map.of("X-Fixture", "yes"),
            transport,
            LIMITS,
            LiveProviderPolicy.disabled())) {
      LLMResponse response = client.complete(request(false));

      assertThat(response.text()).isEqualTo("answer");
      assertThat(response.provider()).isEqualTo("openai_compatible");
      assertThat(response.requestId()).isEqualTo("header-id");
      assertThat(response.inputTokens()).isEqualTo(3);
      assertThat(response.outputTokens()).isEqualTo(2);
      assertThat(response.metadata().toString())
          .doesNotContain("private reasoning")
          .contains("\"characters\":17", "\"sha256\"");
      assertThat(transport.lastRequest().uri().toString())
          .endsWith("/v1/chat/completions");
      assertThat(transport.lastRequest().headers())
          .containsEntry("Authorization", "Bearer fixture-secret")
          .containsEntry("X-Fixture", "yes");
      assertThat(
              ContractObjectMapper.parseTree(
                      new String(
                          transport.lastRequest().body(),
                          StandardCharsets.UTF_8))
                  .path("response_format")
                  .path("type")
                  .asText())
          .isEqualTo("json_object");
    }
  }

  @Test
  void deepSeekThinkingRequestAndFragmentedSseAreMappedWithoutReasoningLeakage() {
    String sse =
        """
        data: {"id":"stream-id","model":"deepseek-reasoner","choices":[{"delta":{"reasoning_content":"hidden"}}]}

        data: {"choices":[{"delta":{"content":"proof"},"finish_reason":"stop"}]}

        data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":5}}

        data: [DONE]

        """;
    FixtureTransport transport =
        new FixtureTransport(
            200,
            Map.of("request-id", List.of("header-stream-id")),
            new FragmentInputStream(sse.getBytes(StandardCharsets.UTF_8), 3),
            false);
    try (DeepSeekClient client =
        new DeepSeekClient(
            SecretValue.of("deepseek-fixture"),
            "deepseek-reasoner",
            BASE,
            Duration.ofSeconds(2),
            Map.of(),
            transport,
            LIMITS,
            LiveProviderPolicy.disabled())) {
      ProviderRequest request =
          new ProviderRequest(
              request(true).messages(),
              0.7d,
              128,
              true,
              null,
              null,
              true,
              "max",
              true,
              "fixture-user",
              null);

      LLMResponse response = client.complete(request);

      assertThat(response.text()).isEqualTo("proof");
      assertThat(response.streaming()).isTrue();
      assertThat(response.inputTokens()).isEqualTo(7);
      assertThat(response.outputTokens()).isEqualTo(5);
      assertThat(response.metadata().toString())
          .doesNotContain("hidden")
          .contains("\"characters\":6", "\"done_received\":true");
      String body =
          new String(transport.lastRequest().body(), StandardCharsets.UTF_8);
      assertThat(body)
          .contains("\"thinking\":{\"type\":\"enabled\"}")
          .contains("\"reasoning_effort\":\"max\"")
          .contains("\"user_id\":\"fixture-user\"")
          .doesNotContain("\"temperature\"");
    }
  }

  @Test
  void anthropicMapsSystemMessagesBlocksUsageAndAuthentication() {
    FixtureTransport transport =
        FixtureTransport.json(
            200,
            """
            {
              "id":"anthropic-id",
              "model":"claude-fixture",
              "content":[
                {"type":"thinking","thinking":"not public"},
                {"type":"text","text":"public answer"}
              ],
              "stop_reason":"end_turn",
              "usage":{"input_tokens":11,"output_tokens":13}
            }
            """,
            Map.of());
    try (AnthropicClient client =
        new AnthropicClient(
            SecretValue.of("anthropic-fixture"),
            "claude-fixture",
            BASE,
            Duration.ofSeconds(2),
            Map.of(),
            transport,
            LIMITS,
            LiveProviderPolicy.disabled())) {
      LLMResponse response = client.complete(request(false));

      assertThat(response.text()).isEqualTo("public answer");
      assertThat(response.provider()).isEqualTo("anthropic");
      assertThat(response.requestId()).isEqualTo("anthropic-id");
      assertThat(response.metadata().toString()).doesNotContain("not public");
      assertThat(transport.lastRequest().headers())
          .containsEntry("x-api-key", "anthropic-fixture")
          .containsEntry("anthropic-version", "2023-06-01");
      String body =
          new String(transport.lastRequest().body(), StandardCharsets.UTF_8);
      assertThat(body)
          .contains("\"system\":\"fixture system\"")
          .doesNotContain("\"role\":\"system\"");
    }
  }

  @Test
  void geminiMapsEndpointSchemaUsageAndThoughtMetadata() {
    FixtureTransport transport =
        FixtureTransport.json(
            200,
            """
            {
              "responseId":"gemini-id",
              "modelVersion":"gemini-fixture",
              "candidates":[{
                "content":{"parts":[
                  {"thought":true,"text":"private thought"},
                  {"text":"public result"}
                ]},
                "finishReason":"STOP"
              }],
              "usageMetadata":{
                "promptTokenCount":17,
                "candidatesTokenCount":19
              }
            }
            """,
            Map.of());
    try (GeminiClient client =
        new GeminiClient(
            SecretValue.of("gemini-fixture"),
            "gemini-fixture",
            BASE,
            Duration.ofSeconds(2),
            Map.of(),
            transport,
            LIMITS,
            LiveProviderPolicy.disabled())) {
      LLMResponse response = client.complete(request(false));

      assertThat(response.text()).isEqualTo("public result");
      assertThat(response.inputTokens()).isEqualTo(17);
      assertThat(response.outputTokens()).isEqualTo(19);
      assertThat(response.metadata().toString()).doesNotContain("private thought");
      assertThat(transport.lastRequest().uri().toString())
          .endsWith("/v1/models/gemini-fixture:generateContent");
      assertThat(transport.lastRequest().headers())
          .containsEntry("x-goog-api-key", "gemini-fixture");
      String body =
          new String(transport.lastRequest().body(), StandardCharsets.UTF_8);
      assertThat(body).contains("\"responseMimeType\":\"application/json\"");
    }
  }

  @Test
  void mockProviderIsDeterministicAndCountsEachCallOnce() {
    MockClient client = new MockClient("mock-model", null);

    assertThat(client.complete(request(false)).requestId()).isEqualTo("mock-1");
    assertThat(client.complete(request(false)).requestId()).isEqualTo("mock-2");
    assertThat(client.calls()).isEqualTo(2);
  }

  @Test
  void httpStatusAndRetryAfterAreMappedWithoutReturningResponseBody() {
    FixtureTransport transport =
        FixtureTransport.json(
            429,
            "{\"error\":\"must-not-escape\"}",
            Map.of("Retry-After", List.of("2.5")));
    try (OpenAICompatibleClient client =
        new OpenAICompatibleClient(
            SecretValue.of("fixture-secret"),
            "fixture-gpt",
            BASE,
            Duration.ofSeconds(2),
            Map.of(),
            transport,
            LIMITS,
            LiveProviderPolicy.disabled())) {
      assertThatThrownBy(() -> client.complete(request(false)))
          .isInstanceOf(ProviderException.class)
          .hasMessage("provider returned HTTP 429")
          .satisfies(
              failure -> {
                ProviderException providerFailure =
                    (ProviderException) failure;
                assertThat(providerFailure.kind())
                    .isEqualTo(ProviderErrorKind.RATE_LIMIT);
                assertThat(providerFailure.retryAfter())
                    .isEqualTo(Duration.ofMillis(2500));
                assertThat(providerFailure.retryable()).isTrue();
              });
    }
  }

  @Test
  void liveNetworkTransportIsDeniedByDefaultBeforeDispatch() {
    FixtureTransport network =
        new FixtureTransport(
            200,
            Map.of(),
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
            true);
    try (OpenAICompatibleClient client =
        new OpenAICompatibleClient(
            SecretValue.of("fixture-secret"),
            "fixture-gpt",
            BASE,
            Duration.ofSeconds(2),
            Map.of(),
            network,
            LIMITS,
            LiveProviderPolicy.disabled())) {
      assertThatThrownBy(() -> client.complete(request(false)))
          .isInstanceOf(ProviderException.class)
          .hasMessageContaining("explicit opt-in")
          .satisfies(
              failure ->
                  assertThat(((ProviderException) failure).kind())
                      .isEqualTo(ProviderErrorKind.LIVE_CALL_DISABLED));
      assertThat(network.sends()).isZero();
    }
  }

  private static ProviderRequest request(boolean streaming) {
    return ProviderRequest.json(
        List.of(
            new ChatMessage("system", "fixture system"),
            new ChatMessage("user", "prove p")),
        128,
        streaming);
  }

  private static final class FixtureTransport implements HttpTransport {
    private final int status;
    private final Map<String, List<String>> headers;
    private final InputStream body;
    private final boolean reachesNetwork;
    private HttpTransportRequest lastRequest;
    private int sends;

    private FixtureTransport(
        int status,
        Map<String, List<String>> headers,
        InputStream body,
        boolean reachesNetwork) {
      this.status = status;
      this.headers = headers;
      this.body = body;
      this.reachesNetwork = reachesNetwork;
    }

    static FixtureTransport json(
        int status, String body, Map<String, List<String>> headers) {
      return new FixtureTransport(
          status,
          headers,
          new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
          false);
    }

    @Override
    public HttpTransportResponse send(HttpTransportRequest request) {
      sends++;
      lastRequest = request;
      return new HttpTransportResponse(status, headers, body);
    }

    @Override
    public boolean reachesNetwork() {
      return reachesNetwork;
    }

    HttpTransportRequest lastRequest() {
      return lastRequest;
    }

    int sends() {
      return sends;
    }
  }

  private static final class FragmentInputStream extends InputStream {
    private final byte[] bytes;
    private final int maximumChunk;
    private int offset;

    private FragmentInputStream(byte[] bytes, int maximumChunk) {
      this.bytes = bytes.clone();
      this.maximumChunk = maximumChunk;
    }

    @Override
    public int read() {
      if (offset >= bytes.length) {
        return -1;
      }
      return Byte.toUnsignedInt(bytes[offset++]);
    }

    @Override
    public int read(byte[] target, int targetOffset, int length) {
      if (offset >= bytes.length) {
        return -1;
      }
      int count =
          Math.min(Math.min(length, maximumChunk), bytes.length - offset);
      System.arraycopy(bytes, offset, target, targetOffset, count);
      offset += count;
      return count;
    }
  }
}
