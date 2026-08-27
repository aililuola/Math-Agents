package io.github.aililuola.mathproofmesh.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Removes identity, social, ranking, prompt, and internal-path metadata. */
public final class BlindReviewPolicy {
  private static final Set<String> FORBIDDEN_KEYS =
      Set.of(
          "agent_id",
          "source_agent_id",
          "reviewer_id",
          "reviewer_identity_hash",
          "route_id",
          "source_route_id",
          "target_route_ids",
          "ranking",
          "score",
          "self_confidence",
          "verification_confidence",
          "normalization_confidence",
          "vote",
          "votes",
          "original_prompt",
          "prompt",
          "private_reasoning",
          "chain_of_thought",
          "internal_path",
          "raw_source_ref",
          "raw_artifact_ref",
          "artifact_refs");

  private BlindReviewPolicy() {}

  public static ObjectNode sanitize(ObjectNode source) {
    java.util.Objects.requireNonNull(source, "source");
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    Iterator<Map.Entry<String, JsonNode>> fields = source.properties().iterator();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      String normalized = field.getKey().toLowerCase(Locale.ROOT);
      if (isForbiddenKey(normalized)) {
        continue;
      }
      result.set(field.getKey(), sanitizeValue(field.getValue()));
    }
    return result;
  }

  public static void assertSafe(JsonNode packet) {
    java.util.Objects.requireNonNull(packet, "packet");
    scan(packet, "$");
  }

  public static Set<String> forbiddenKeys() {
    return FORBIDDEN_KEYS;
  }

  private static boolean isForbiddenKey(String normalized) {
    return FORBIDDEN_KEYS.contains(normalized)
        || normalized.endsWith("_confidence")
        || normalized.endsWith("_score")
        || normalized.endsWith("_vote");
  }

  private static JsonNode sanitizeValue(JsonNode value) {
    if (value.isObject()) {
      return sanitize((ObjectNode) value);
    }
    if (value.isArray()) {
      ArrayNode result = JsonNodeFactory.instance.arrayNode();
      value.forEach(item -> result.add(sanitizeValue(item)));
      return result;
    }
    return value.deepCopy();
  }

  private static void scan(JsonNode value, String path) {
    if (value.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields =
          ((ObjectNode) value).properties().iterator();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String normalized = field.getKey().toLowerCase(Locale.ROOT);
        if (isForbiddenKey(normalized)) {
          throw new IllegalArgumentException(
              "blind packet contains forbidden field at " + path + "." + field.getKey());
        }
        scan(field.getValue(), path + "." + field.getKey());
      }
    } else if (value.isArray()) {
      int index = 0;
      for (JsonNode item : value) {
        scan(item, path + "[" + index++ + "]");
      }
    }
  }
}
