package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ComputationJson {
  private ComputationJson() {}

  static ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }

  static ArrayNode array() {
    return JsonNodeFactory.instance.arrayNode();
  }

  static BigInteger integer(JsonNode node, String label) {
    if (node == null || node.isNull() || node.isBoolean()) {
      throw new IllegalArgumentException(label + " must be an integer");
    }
    try {
      if (node.isIntegralNumber()) {
        return node.bigIntegerValue();
      }
      if (node.isTextual() && node.textValue().trim().matches("[+-]?\\d+")) {
        return new BigInteger(node.textValue().trim());
      }
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(label + " must be an integer", exception);
    }
    throw new IllegalArgumentException(label + " must be an integer");
  }

  static int boundedInt(JsonNode node, String label, int minimum, int maximum) {
    BigInteger value = integer(node, label);
    if (value.compareTo(BigInteger.valueOf(minimum)) < 0
        || value.compareTo(BigInteger.valueOf(maximum)) > 0) {
      throw new IllegalArgumentException(
          label + " must be between " + minimum + " and " + maximum);
    }
    return value.intValue();
  }

  static String requiredText(JsonNode node, String label) {
    if (node == null || !node.isTextual() || node.textValue().isBlank()) {
      throw new IllegalArgumentException(label + " must be a non-empty string");
    }
    return node.textValue().trim();
  }

  static ObjectNode requiredObject(JsonNode node, String label) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(label + " must be a typed mapping");
    }
    return ((ObjectNode) node).deepCopy();
  }

  static ArrayNode requiredArray(JsonNode node, String label) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException(label + " must be a list");
    }
    return ((ArrayNode) node).deepCopy();
  }

  static List<String> textList(JsonNode node, String label) {
    ArrayNode array = requiredArray(node, label);
    List<String> result = new ArrayList<>(array.size());
    for (int index = 0; index < array.size(); index++) {
      JsonNode item = array.get(index);
      if (!item.isValueNode() || item.isContainerNode()) {
        throw new IllegalArgumentException(label + " items must be scalar values");
      }
      result.add(item.asText());
    }
    return List.copyOf(result);
  }

  static Map<String, JsonNode> sortedFields(ObjectNode node) {
    Map<String, JsonNode> fields = new TreeMap<>();
    for (Map.Entry<String, JsonNode> entry : node.properties()) {
      fields.put(entry.getKey(), entry.getValue());
    }
    return fields;
  }

  static void putBigInteger(ObjectNode node, String field, BigInteger value) {
    node.put(field, value);
  }

  static boolean hashesEqual(String left, String right) {
    return left != null
        && right != null
        && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }
}
