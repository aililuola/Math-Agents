package io.github.aililuola.mathproofmesh.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.config.SecretValue;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeepSeekClient extends OpenAICompatibleClient {
  public DeepSeekClient(
      SecretValue apiKey,
      String model,
      URI baseUri,
      Duration timeout,
      Map<String, String> extraHeaders,
      HttpTransport transport,
      ProviderLimits limits,
      LiveProviderPolicy livePolicy) {
    super(
        "deepseek",
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
  protected JsonNode requestBody(ProviderRequest request) {
    ObjectNode payload = (ObjectNode) super.requestBody(request);
    boolean thinking =
        request.thinkingEnabled() == null || request.thinkingEnabled();
    payload.putObject("thinking").put("type", thinking ? "enabled" : "disabled");
    if (thinking) {
      payload.remove("temperature");
      payload.put(
          "reasoning_effort",
          request.reasoningEffort() == null ? "high" : request.reasoningEffort());
    }
    if (request.userId() != null) {
      payload.remove("user");
      payload.put("user_id", request.userId());
    }
    return payload;
  }

  @Override
  public List<String> listModels() {
    JsonNode payload =
        getJson(
            appendPath(baseUri, "models"),
            Map.of("Authorization", "Bearer " + secretText()));
    JsonNode data = payload.path("data");
    if (!data.isArray()) {
      throw ProviderException.protocol(
          "DeepSeek model list has no data array", null);
    }
    List<String> models = new ArrayList<>();
    for (JsonNode item : data) {
      String id = item.path("id").asText("").strip();
      if (!id.isEmpty()) {
        models.add(id);
      }
    }
    return List.copyOf(models);
  }
}
