package io.github.aililuola.mathproofmesh.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProviderJson {
  private ProviderJson() {}

  static JsonNode parse(String json) {
    return ContractObjectMapper.parseTree(json);
  }

  static byte[] write(JsonNode value) {
    return ContractObjectMapper.write(value).getBytes(StandardCharsets.UTF_8);
  }

  static ArrayNode messages(List<ChatMessage> messages) {
    ArrayNode result = JsonNodeFactory.instance.arrayNode();
    for (ChatMessage message : messages) {
      result.addObject()
          .put("role", message.role())
          .put("content", message.content());
    }
    return result;
  }

  static String textValue(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return "";
    }
    if (value.isArray()) {
      StringBuilder result = new StringBuilder();
      for (JsonNode item : value) {
        if (item.isObject() && item.has("text")) {
          result.append(item.path("text").asText(""));
        } else if (item.isTextual()) {
          result.append(item.textValue());
        }
      }
      return result.toString();
    }
    return value.asText("");
  }

  static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the JDK", exception);
    }
  }

  static ObjectNode safeMetadata(
      JsonNode usage,
      String reasoning,
      boolean streaming,
      int chunks,
      boolean done) {
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("streaming", streaming);
    if (usage != null && !usage.isMissingNode() && !usage.isNull()) {
      metadata.set("usage", usage.deepCopy());
    } else {
      metadata.set("usage", JsonNodeFactory.instance.objectNode());
    }
    ObjectNode reasoningNode = metadata.putObject("reasoning");
    reasoningNode.put("present", !reasoning.isEmpty());
    reasoningNode.put("characters", reasoning.length());
    if (reasoning.isEmpty()) {
      reasoningNode.putNull("sha256");
    } else {
      reasoningNode.put("sha256", sha256(reasoning));
    }
    if (streaming) {
      ObjectNode stream = metadata.putObject("stream");
      stream.put("chunks", chunks);
      stream.put("done_received", done);
    }
    return metadata;
  }

  static Map<String, String> cleanHeaders(Map<String, String> headers) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      String name = entry.getKey().strip();
      String value = entry.getValue().strip();
      if (name.isEmpty()
          || value.isEmpty()
          || name.indexOf('\r') >= 0
          || name.indexOf('\n') >= 0
          || value.indexOf('\r') >= 0
          || value.indexOf('\n') >= 0) {
        throw new IllegalArgumentException("provider headers must be non-empty single lines");
      }
      result.put(name, value);
    }
    return Map.copyOf(result);
  }

  static List<ChatMessage> withoutRole(
      List<ChatMessage> messages, String excludedRole) {
    List<ChatMessage> result = new ArrayList<>();
    for (ChatMessage message : messages) {
      if (!excludedRole.equals(message.role())) {
        result.add(message);
      }
    }
    return List.copyOf(result);
  }

  static String joinedRole(List<ChatMessage> messages, String role) {
    return messages.stream()
        .filter(message -> role.equals(message.role()))
        .map(ChatMessage::content)
        .reduce((left, right) -> left + "\n\n" + right)
        .orElse("");
  }
}
