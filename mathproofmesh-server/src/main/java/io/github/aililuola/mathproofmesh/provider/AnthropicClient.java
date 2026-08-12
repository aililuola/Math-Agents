package io.github.aililuola.mathproofmesh.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.config.SecretValue;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class AnthropicClient extends AbstractHttpProvider {
  public AnthropicClient(
      SecretValue apiKey,
      String model,
      URI baseUri,
      Duration timeout,
      Map<String, String> extraHeaders,
      HttpTransport transport,
      ProviderLimits limits,
      LiveProviderPolicy livePolicy) {
    super(
        "anthropic",
        apiKey,
        model,
        baseUri,
        timeout,
        extraHeaders,
        transport,
        limits,
        livePolicy);
  }

  @Override
  protected URI endpoint(ProviderRequest request) {
    return appendPath(baseUri, "messages");
  }

  @Override
  protected Map<String, String> headers(ProviderRequest request) {
    return Map.of(
        "x-api-key", secretText(),
        "anthropic-version", "2023-06-01");
  }

  @Override
  protected JsonNode requestBody(ProviderRequest request) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("model", model);
    payload.put("max_tokens", request.maxOutputTokens());
    payload.put("temperature", request.temperature());
    payload.put("stream", request.streaming());
    String system = ProviderJson.joinedRole(request.messages(), "system");
    if (!system.isEmpty()) {
      payload.put("system", system);
    }
    payload.set(
        "messages",
        ProviderJson.messages(
            ProviderJson.withoutRole(request.messages(), "system")));
    return payload;
  }

  @Override
  protected LLMResponse parseResponse(
      JsonNode payload,
      String requestId,
      double latencyMs,
      ProviderRequest request) {
    StringBuilder text = new StringBuilder();
    StringBuilder reasoning = new StringBuilder();
    JsonNode content = payload.path("content");
    if (!content.isArray()) {
      throw ProviderException.protocol("Anthropic response has no content array", null);
    }
    for (JsonNode block : content) {
      String type = block.path("type").asText("");
      if ("text".equals(type)) {
        text.append(block.path("text").asText(""));
      } else if ("thinking".equals(type)) {
        reasoning.append(block.path("thinking").asText(""));
      }
    }
    JsonNode usage = payload.path("usage");
    return new LLMResponse(
        text.toString(),
        payload.path("model").asText(model),
        providerId(),
        usage.path("input_tokens").asLong(0L),
        usage.path("output_tokens").asLong(0L),
        latencyMs,
        OpenAICompatibleClient.firstNonBlank(
            requestId, payload.path("id").asText(null)),
        payload.path("stop_reason").asText(null),
        false,
        ProviderJson.safeMetadata(
            usage, reasoning.toString(), false, 0, true));
  }

  @Override
  protected LLMResponse parseStream(
      List<String> events,
      String requestId,
      double latencyMs,
      ProviderRequest request) {
    StringBuilder text = new StringBuilder();
    StringBuilder reasoning = new StringBuilder();
    ObjectNode usage = JsonNodeFactory.instance.objectNode();
    String responseId = requestId;
    String responseModel = model;
    String finishReason = null;
    int chunks = 0;
    boolean done = false;
    for (String event : events) {
      JsonNode chunk = ProviderJson.parse(event);
      chunks++;
      String type = chunk.path("type").asText("");
      if ("error".equals(type)) {
        throw ProviderException.protocol("Anthropic SSE reported an error", null);
      }
      if ("message_start".equals(type)) {
        JsonNode message = chunk.path("message");
        responseId =
            OpenAICompatibleClient.firstNonBlank(
                responseId, message.path("id").asText(null));
        responseModel =
            OpenAICompatibleClient.firstNonBlank(
                message.path("model").asText(null), responseModel);
        mergeUsage(usage, message.path("usage"));
      } else if ("content_block_delta".equals(type)) {
        JsonNode delta = chunk.path("delta");
        String deltaType = delta.path("type").asText("");
        if ("text_delta".equals(deltaType)) {
          text.append(delta.path("text").asText(""));
        } else if ("thinking_delta".equals(deltaType)) {
          reasoning.append(delta.path("thinking").asText(""));
        }
      } else if ("message_delta".equals(type)) {
        mergeUsage(usage, chunk.path("usage"));
        finishReason = chunk.path("delta").path("stop_reason").asText(null);
      } else if ("message_stop".equals(type)) {
        done = true;
      }
    }
    if (!done) {
      throw ProviderException.protocol(
          "Anthropic SSE stream ended before message_stop", null);
    }
    return new LLMResponse(
        text.toString(),
        responseModel,
        providerId(),
        usage.path("input_tokens").asLong(0L),
        usage.path("output_tokens").asLong(0L),
        latencyMs,
        responseId,
        finishReason,
        true,
        ProviderJson.safeMetadata(
            usage, reasoning.toString(), true, chunks, true));
  }

  private static void mergeUsage(ObjectNode target, JsonNode source) {
    if (source.isObject()) {
      source.properties().forEach(entry -> target.set(entry.getKey(), entry.getValue()));
    }
  }
}
