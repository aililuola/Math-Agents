package io.github.aililuola.mathproofmesh.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PromptContextNormalizer {
  private PromptContextNormalizer() {}

  public static Object normalize(Object value) {
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Path path) {
      return path.toString();
    }
    if (value instanceof Enum<?> enumeration) {
      JsonNode node = ContractObjectMapper.toTree(enumeration);
      return node.isTextual() ? node.textValue() : node.toString();
    }
    if (value instanceof JsonNode node) {
      return node.deepCopy();
    }
    if (value instanceof Map<?, ?> map) {
      List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
      entries.sort(
          Comparator.comparing(entry -> String.valueOf(entry.getKey())));
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : entries) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException(
              "prompt context map keys must be strings");
        }
        normalized.put(key, normalize(entry.getValue()));
      }
      return java.util.Collections.unmodifiableMap(normalized);
    }
    if (value instanceof Set<?> set) {
      return set.stream()
          .map(PromptContextNormalizer::normalize)
          .sorted(
              Comparator.comparing(ContractObjectMapper::write))
          .toList();
    }
    if (value instanceof Iterable<?> iterable) {
      List<Object> normalized = new ArrayList<>();
      iterable.forEach(item -> normalized.add(normalize(item)));
      return java.util.Collections.unmodifiableList(normalized);
    }
    if (value.getClass().isArray()) {
      List<Object> normalized = new ArrayList<>();
      for (int index = 0; index < Array.getLength(value); index++) {
        normalized.add(normalize(Array.get(value, index)));
      }
      return java.util.Collections.unmodifiableList(normalized);
    }
    if (value.getClass().isRecord()) {
      return normalizeRecord(value);
    }
    return ContractObjectMapper.toTree(value);
  }

  private static Map<String, Object> normalizeRecord(Object value) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    for (RecordComponent component : value.getClass().getRecordComponents()) {
      if (component.isAnnotationPresent(JsonIgnore.class)
          || component.getAccessor().isAnnotationPresent(JsonIgnore.class)) {
        continue;
      }
      JsonProperty property = component.getAnnotation(JsonProperty.class);
      if (property == null) {
        property = component.getAccessor().getAnnotation(JsonProperty.class);
      }
      String name =
          property == null || property.value().isEmpty()
              ? component.getName()
              : property.value();
      try {
        normalized.put(
            name, normalize(component.getAccessor().invoke(value)));
      } catch (ReflectiveOperationException exception) {
        throw new IllegalArgumentException(
            "could not normalize prompt record "
                + value.getClass().getName(),
            exception);
      }
    }
    return java.util.Collections.unmodifiableMap(normalized);
  }
}
