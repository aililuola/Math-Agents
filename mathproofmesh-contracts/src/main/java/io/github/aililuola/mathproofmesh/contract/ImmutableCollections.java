package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ImmutableCollections {
  private ImmutableCollections() {}

  public static <T> List<T> listOrEmpty(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  public static <T> List<T> requiredList(String name, List<T> values) {
    if (values == null) {
      throw new ContractValidationException(name + " is required");
    }
    return List.copyOf(values);
  }

  public static <T> List<T> nullableList(List<T> values) {
    return values == null ? null : List.copyOf(values);
  }

  public static <K, V> Map<K, V> mapOrEmpty(Map<K, V> values) {
    return values == null ? Map.of() : Map.copyOf(values);
  }

  public static <K, V> Map<K, V> requiredMap(String name, Map<K, V> values) {
    if (values == null) {
      throw new ContractValidationException(name + " is required");
    }
    return Map.copyOf(values);
  }

  public static <K, V> Map<K, V> nullableMap(Map<K, V> values) {
    return values == null ? null : Map.copyOf(values);
  }

  public static <T extends JsonNode> List<T> jsonListOrEmpty(List<T> values) {
    return values == null ? List.of() : copyJsonList(values);
  }

  public static <T extends JsonNode> List<T> requiredJsonList(
      String name, List<T> values) {
    if (values == null) {
      throw new ContractValidationException(name + " is required");
    }
    return copyJsonList(values);
  }

  public static <T extends JsonNode> List<T> nullableJsonList(List<T> values) {
    return values == null ? null : copyJsonList(values);
  }

  public static <T extends JsonNode> List<T> copyJsonList(List<T> values) {
    return values.stream().map(ImmutableCollections::copyJsonNode).toList();
  }

  public static Map<String, JsonNode> jsonMapOrEmpty(
      Map<String, JsonNode> values) {
    return values == null ? Map.of() : copyJsonMap(values);
  }

  public static Map<String, JsonNode> requiredJsonMap(
      String name, Map<String, JsonNode> values) {
    if (values == null) {
      throw new ContractValidationException(name + " is required");
    }
    return copyJsonMap(values);
  }

  public static Map<String, JsonNode> nullableJsonMap(
      Map<String, JsonNode> values) {
    return values == null ? null : copyJsonMap(values);
  }

  public static Map<String, JsonNode> copyJsonMap(
      Map<String, JsonNode> values) {
    Map<String, JsonNode> copy = new LinkedHashMap<>();
    values.forEach((key, value) -> copy.put(key, copyJsonNode(value)));
    return Map.copyOf(copy);
  }

  public static Map<String, List<String>> stringListMapOrEmpty(
      Map<String, List<String>> values) {
    return values == null ? Map.of() : copyStringListMap(values);
  }

  public static Map<String, List<String>> requiredStringListMap(
      String name, Map<String, List<String>> values) {
    if (values == null) {
      throw new ContractValidationException(name + " is required");
    }
    return copyStringListMap(values);
  }

  public static Map<String, List<String>> nullableStringListMap(
      Map<String, List<String>> values) {
    return values == null ? null : copyStringListMap(values);
  }

  public static Map<String, List<String>> copyStringListMap(
      Map<String, List<String>> values) {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    values.forEach((key, value) -> copy.put(key, List.copyOf(value)));
    return Map.copyOf(copy);
  }

  @SuppressWarnings("unchecked")
  private static <T extends JsonNode> T copyJsonNode(T value) {
    return (T) value.deepCopy();
  }
}
