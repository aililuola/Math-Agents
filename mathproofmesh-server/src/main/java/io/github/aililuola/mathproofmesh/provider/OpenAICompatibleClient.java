package io.github.aililuola.mathproofmesh.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceCall;
import io.github.aililuola.mathproofmesh.config.SecretValue;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class OpenAICompatibleClient extends AbstractHttpProvider {
  public OpenAICompatibleClient(
      SecretValue apiKey,
      String model,
      URI baseUri,
      Duration timeout,
      Map<String, String> extraHeaders,
      HttpTransport transport,
      ProviderLimits limits,
      LiveProviderPolicy livePolicy) {
    this(
        "openai_compatible",
        apiKey,
        model,
        baseUri,
        timeout,
        extraHeaders,
        transport,
        limits,
        livePolicy);
  }

  protected OpenAICompatibleClient(
      String providerId,
      SecretValue apiKey,
      String model,
      URI baseUri,
      Duration timeout,
      Map<String, String> extraHeaders,
      HttpTransport transport,
      ProviderLimits limits,
      LiveProviderPolicy livePolicy) {
    super(
        providerId,
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
    return appendPath(baseUri, "chat/completions");
  }

  @Override
  protected Map<String, String> headers(ProviderRequest request) {
    return Map.of("Authorization", "Bearer " + secretText());
  }

  @Override
  protected JsonNode requestBody(ProviderRequest request) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("model", model);
    payload.set("messages", ProviderJson.messages(request.messages()));
    payload.put("temperature", request.temperature());
    payload.put("max_tokens", request.maxOutputTokens());
    payload.put("stream", request.streaming());
    if (request.streaming()) {
      payload.putObject("stream_options").put("include_usage", true);
    }
    if (request.jsonMode()) {
      payload.putObject("response_format").put("type", "json_object");
    }
    if (request.userId() != null) {
      payload.put("user", request.userId());
    }
    return payload;
  }

  @Override
  protected LLMResponse parseResponse(
      JsonNode payload,
      String requestId,
      double latencyMs,
      ProviderRequest request) {
    JsonNode choice = firstChoice(payload);
    JsonNode message = choice.path("message");
    String content = ProviderJson.textValue(message.path("content"));
    String reasoning = ProviderJson.textValue(message.path("reasoning_content"));
    JsonNode usage = payload.path("usage");
    String responseModel = payload.path("model").asText(model);
    String id = firstNonBlank(requestId, payload.path("id").asText(null));
    return new LLMResponse(
        content,
        responseModel,
        providerId(),
        usage.path("prompt_tokens").asLong(0L),
        usage.path("completion_tokens").asLong(0L),
        latencyMs,
        id,
        choice.path("finish_reason").asText(null),
        false,
        ProviderJson.safeMetadata(usage, reasoning, false, 0, true));
  }

  @Override
  protected LLMResponse parseStream(
      List<String> events,
      String requestId,
      double latencyMs,
      ProviderRequest request) {
    StringBuilder content = new StringBuilder();
    StringBuilder reasoning = new StringBuilder();
    JsonNode usage = JsonNodeFactory.instance.objectNode();
    String responseModel = model;
    String responseId = requestId;
    String finishReason = null;
    int chunks = 0;
    boolean done = false;
    for (String event : events) {
      if ("[DONE]".equals(event)) {
        done = true;
        break;
      }
      JsonNode chunk = ProviderJson.parse(event);
      if (chunk.has("error") && !chunk.path("error").isNull()) {
        throw ProviderException.protocol("provider SSE reported an error", null);
      }
      chunks++;
      responseId = firstNonBlank(responseId, chunk.path("id").asText(null));
      responseModel = firstNonBlank(chunk.path("model").asText(null), responseModel);
      if (chunk.path("usage").isObject()) {
        usage = chunk.path("usage");
      }
      JsonNode choices = chunk.path("choices");
      if (!choices.isArray() || choices.isEmpty()) {
        continue;
      }
      JsonNode choice = choices.get(0);
      JsonNode delta = choice.path("delta");
      content.append(ProviderJson.textValue(delta.path("content")));
      reasoning.append(ProviderJson.textValue(delta.path("reasoning_content")));
      finishReason =
          firstNonBlank(choice.path("finish_reason").asText(null), finishReason);
    }
    if (!done) {
      throw ProviderException.protocol(
          "provider SSE stream ended before data: [DONE]", null);
    }
    if (!usage.isObject() || usage.isEmpty()) {
      throw ProviderException.protocol(
          "provider SSE stream ended without requested usage", null);
    }
    return new LLMResponse(
        content.toString(),
        responseModel,
        providerId(),
        usage.path("prompt_tokens").asLong(0L),
        usage.path("completion_tokens").asLong(0L),
        latencyMs,
        responseId,
        finishReason,
        true,
        ProviderJson.safeMetadata(
            usage, reasoning.toString(), true, chunks, true));
  }

  @Override
  protected void observeStreamingEvent(String event, ReasoningTraceCall traceCall) {
    if (traceCall == null || "[DONE]".equals(event)) {
      return;
    }
    JsonNode chunk = ProviderJson.parse(event);
    JsonNode choices = chunk.path("choices");
    if (!choices.isArray() || choices.isEmpty()) {
      return;
    }
    String reasoning =
        ProviderJson.textValue(choices.get(0).path("delta").path("reasoning_content"));
    if (!reasoning.isEmpty()) {
      traceCall.append(reasoning);
    }
    traceCall.flushDue();
  }

  @Override
  protected void observeResponse(JsonNode payload, ReasoningTraceCall traceCall) {
    if (traceCall == null) {
      return;
    }
    String reasoning =
        ProviderJson.textValue(firstChoice(payload).path("message").path("reasoning_content"));
    if (!reasoning.isEmpty()) {
      traceCall.append(reasoning);
    }
  }

  @Override
  protected ProviderException interruptedStream(
      List<String> events, Throwable cause) {
    StringBuilder content = new StringBuilder();
    int reasoningCharacters = 0;
    for (String event : events) {
      if ("[DONE]".equals(event)) {
        continue;
      }
      try {
        JsonNode chunk = ProviderJson.parse(event);
        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
          continue;
        }
        JsonNode delta = choices.get(0).path("delta");
        content.append(ProviderJson.textValue(delta.path("content")));
        reasoningCharacters +=
            ProviderJson.textValue(
                    delta.path("reasoning_content"))
                .length();
      } catch (RuntimeException ignored) {
        // A truncated final event is not safe to interpret.
      }
    }
    return ProviderException.network(
        cause, content.toString(), reasoningCharacters);
  }

  private static JsonNode firstChoice(JsonNode payload) {
    JsonNode choices = payload.path("choices");
    if (!choices.isArray() || choices.isEmpty() || !choices.get(0).isObject()) {
      throw ProviderException.protocol("provider response has no choice", null);
    }
    return choices.get(0);
  }

  protected static String firstNonBlank(String preferred, String fallback) {
    if (preferred != null && !preferred.isBlank()) {
      return preferred;
    }
    return fallback;
  }
}
