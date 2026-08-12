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

public class GeminiClient extends AbstractHttpProvider {
  public GeminiClient(
      SecretValue apiKey,
      String model,
      URI baseUri,
      Duration timeout,
      Map<String, String> extraHeaders,
      HttpTransport transport,
      ProviderLimits limits,
      LiveProviderPolicy livePolicy) {
    super(
        "gemini",
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
    String operation =
        request.streaming() ? ":streamGenerateContent?alt=sse" : ":generateContent";
    return appendPath(baseUri, "models/" + model + operation);
  }

  @Override
  protected Map<String, String> headers(ProviderRequest request) {
    return Map.of("x-goog-api-key", secretText());
  }

  @Override
  protected JsonNode requestBody(ProviderRequest request) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    String system = ProviderJson.joinedRole(request.messages(), "system");
    if (!system.isEmpty()) {
      payload.putObject("systemInstruction")
          .putArray("parts")
          .addObject()
          .put("text", system);
    }
    ArrayNode contents = payload.putArray("contents");
    for (ChatMessage message :
        ProviderJson.withoutRole(request.messages(), "system")) {
      String role = "assistant".equals(message.role()) ? "model" : "user";
      contents.addObject()
          .put("role", role)
          .putArray("parts")
          .addObject()
          .put("text", message.content());
    }
    ObjectNode generation = payload.putObject("generationConfig");
    generation.put("temperature", request.temperature());
    generation.put("maxOutputTokens", request.maxOutputTokens());
    if (request.jsonMode()) {
      generation.put("responseMimeType", "application/json");
      if (request.schema() != null) {
        generation.set("responseSchema", request.schema());
      }
    }
    return payload;
  }

  @Override
  protected LLMResponse parseResponse(
      JsonNode payload,
      String requestId,
      double latencyMs,
      ProviderRequest request) {
    Accumulator accumulator = new Accumulator(model, requestId);
    accumulator.accept(payload);
    return accumulator.response(providerId(), latencyMs, false, 1, true);
  }

  @Override
  protected LLMResponse parseStream(
      List<String> events,
      String requestId,
      double latencyMs,
      ProviderRequest request) {
    Accumulator accumulator = new Accumulator(model, requestId);
    int chunks = 0;
    boolean done = false;
    for (String event : events) {
      if ("[DONE]".equals(event)) {
        done = true;
        break;
      }
      accumulator.accept(ProviderJson.parse(event));
      chunks++;
    }
    // Gemini may close a successful SSE stream without an OpenAI-style marker.
    done = done || !events.isEmpty();
    if (!done) {
      throw ProviderException.protocol("Gemini SSE stream returned no events", null);
    }
    return accumulator.response(providerId(), latencyMs, true, chunks, done);
  }

  private static final class Accumulator {
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private JsonNode usage = JsonNodeFactory.instance.objectNode();
    private String model;
    private String requestId;
    private String finishReason;

    private Accumulator(String model, String requestId) {
      this.model = model;
      this.requestId = requestId;
    }

    private void accept(JsonNode payload) {
      if (payload.has("error")) {
        throw ProviderException.protocol("Gemini response reported an error", null);
      }
      model =
          OpenAICompatibleClient.firstNonBlank(
              payload.path("modelVersion").asText(null), model);
      requestId =
          OpenAICompatibleClient.firstNonBlank(
              requestId, payload.path("responseId").asText(null));
      if (payload.path("usageMetadata").isObject()) {
        usage = payload.path("usageMetadata");
      }
      JsonNode candidates = payload.path("candidates");
      if (!candidates.isArray()) {
        return;
      }
      for (JsonNode candidate : candidates) {
        finishReason =
            OpenAICompatibleClient.firstNonBlank(
                candidate.path("finishReason").asText(null), finishReason);
        JsonNode parts = candidate.path("content").path("parts");
        if (!parts.isArray()) {
          continue;
        }
        for (JsonNode part : parts) {
          if (part.path("thought").asBoolean(false)) {
            reasoning.append(part.path("text").asText(""));
          } else {
            text.append(part.path("text").asText(""));
          }
        }
      }
    }

    private LLMResponse response(
        String provider,
        double latencyMs,
        boolean streaming,
        int chunks,
        boolean done) {
      return new LLMResponse(
          text.toString(),
          model,
          provider,
          usage.path("promptTokenCount").asLong(0L),
          usage.path("candidatesTokenCount").asLong(0L),
          latencyMs,
          requestId,
          finishReason,
          streaming,
          ProviderJson.safeMetadata(
              usage, reasoning.toString(), streaming, chunks, done));
    }
  }
}
