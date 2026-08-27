package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

final class ConfigShapeValidator {
  private ConfigShapeValidator() {}

  static void validateSystemConfig(JsonNode root) {
    validate(root, SystemConfig.class, "configuration", false);
  }

  static void validateValue(JsonNode value, Class<? extends ConfigModel> type) {
    validate(value, type, type.getSimpleName(), false);
  }

  private static void validate(
      JsonNode node, Type expected, String path, boolean nullable) {
    if (node == null || node.isMissingNode()) {
      throw invalid(path, "is required");
    }
    if (node.isNull()) {
      if (!nullable) {
        throw invalid(path, "cannot be null");
      }
      return;
    }

    if (expected instanceof ParameterizedType parameterized) {
      validateParameterized(node, parameterized, path);
      return;
    }
    if (!(expected instanceof Class<?> expectedClass)) {
      throw invalid(path, "uses an unsupported schema type");
    }
    if (ConfigModel.class.isAssignableFrom(expectedClass)) {
      validateRecord(node, expectedClass, path);
    } else if (expectedClass == String.class
        || expectedClass == SecretValue.class
        || expectedClass == ComputationPurpose.class
        || expectedClass.isEnum()) {
      require(node.isTextual(), path, "must be a string");
    } else if (expectedClass == Integer.class) {
      require(
          node.isIntegralNumber() && node.canConvertToInt(),
          path,
          "must be a 32-bit integer");
    } else if (expectedClass == Double.class) {
      require(
          node.isNumber() && Double.isFinite(node.doubleValue()),
          path,
          "must be a finite number");
    } else if (expectedClass == Boolean.class) {
      require(node.isBoolean(), path, "must be a boolean");
    } else {
      throw invalid(path, "uses an unsupported schema type");
    }
  }

  private static void validateParameterized(
      JsonNode node, ParameterizedType expected, String path) {
    Type raw = expected.getRawType();
    if (raw == java.util.List.class) {
      require(node.isArray(), path, "must be a sequence");
      Type itemType = expected.getActualTypeArguments()[0];
      for (int index = 0; index < node.size(); index++) {
        validate(node.get(index), itemType, path + "[" + index + "]", false);
      }
      return;
    }
    if (raw == java.util.Map.class) {
      require(node.isObject(), path, "must be a mapping");
      Type valueType = expected.getActualTypeArguments()[1];
      Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        validate(entry.getValue(), valueType, path + "." + entry.getKey(), false);
      }
      return;
    }
    throw invalid(path, "uses an unsupported parameterized schema type");
  }

  private static void validateRecord(JsonNode node, Class<?> type, String path) {
    require(node.isObject(), path, "must be a mapping");
    Map<String, RecordComponent> components = new HashMap<>();
    Set<String> required = new HashSet<>();
    for (RecordComponent component : type.getRecordComponents()) {
      JsonProperty property =
          component.getAccessor().getAnnotation(JsonProperty.class);
      String name =
          property == null || property.value().isBlank()
              ? component.getName()
              : property.value();
      components.put(name, component);
      if (property != null && property.required()) {
        required.add(name);
      }
    }

    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      RecordComponent component = components.get(name);
      if (component == null) {
        throw invalid(path + "." + name, "is not a recognized field");
      }
      boolean nullable = component.isAnnotationPresent(ConfigNullable.class);
      validate(
          node.get(name),
          component.getGenericType(),
          path + "." + name,
          nullable);
    }
    for (String name : required) {
      if (!node.has(name)) {
        throw invalid(path + "." + name, "is required");
      }
    }
  }

  private static void require(boolean condition, String path, String detail) {
    if (!condition) {
      throw invalid(path, detail);
    }
  }

  private static ConfigValidationException invalid(String path, String detail) {
    return new ConfigValidationException(path + " " + detail);
  }
}
