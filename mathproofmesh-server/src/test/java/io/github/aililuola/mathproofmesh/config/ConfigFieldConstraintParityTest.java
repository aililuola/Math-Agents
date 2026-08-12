package io.github.aililuola.mathproofmesh.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigFieldConstraintParityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final StrictYamlConfigLoader LOADER = new StrictYamlConfigLoader();
  private static final SystemConfig MINIMAL =
      LOADER.read(
          "agents:\n"
              + "  - id: mock-agent\n"
              + "    provider: mock\n"
              + "    model: mock-model\n");

  @Test
  void generatedCatalogCoversEveryAuthoritativeFieldConstraint() {
    assertEquals(577, ConfigConstraintCatalog.constraints().size());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("constraints")
  void eachPydanticFieldConstraintRejectsAnEquivalentInvalidValue(
      ConfigFieldConstraint constraint) {
    ObjectNode node = validNode(constraint.recordType());
    applyInvalidValue(node, constraint);

    assertThrows(
        ConfigValidationException.class,
        () -> bind(node, constraint.recordType()));
  }

  static Stream<ConfigFieldConstraint> constraints() {
    return ConfigConstraintCatalog.constraints().stream();
  }

  private static ObjectNode validNode(Class<? extends ConfigModel> type) {
    if (type == AgentConfig.class) {
      return JSON.valueToTree(MINIMAL.agents().getFirst());
    }
    if (type == SystemConfig.class) {
      return JSON.valueToTree(MINIMAL);
    }
    if (type == ExplorationTierPolicyConfig.class) {
      return JSON.valueToTree(new ExplorationTierPolicyConfig(64000, 8000));
    }
    try {
      Method defaults = type.getMethod("defaults");
      return JSON.valueToTree(defaults.invoke(null));
    } catch (NoSuchMethodException
        | IllegalAccessException
        | InvocationTargetException exception) {
      throw new AssertionError("missing generated default fixture for " + type.getName(), exception);
    }
  }

  private static void applyInvalidValue(
      ObjectNode node, ConfigFieldConstraint constraint) {
    String field = constraint.field();
    Object bound = constraint.values().getFirst();
    switch (constraint.kind()) {
      case MINIMUM -> putNumber(node, field, below((Number) bound));
      case EXCLUSIVE_MINIMUM -> putNumber(node, field, (Number) bound);
      case MAXIMUM -> putNumber(node, field, above((Number) bound));
      case MINIMUM_LENGTH -> makeTooShort(node, field);
      case MAXIMUM_LENGTH ->
          makeTooLong(node, field, ((Number) bound).intValue());
      case ONE_OF -> {
        if (bound instanceof Boolean booleanValue) {
          node.put(field, !booleanValue);
        } else {
          node.put(field, "__unsupported__");
        }
      }
      case ITEMS_ONE_OF -> {
        ArrayNode values = (ArrayNode) node.path(field);
        if (values.isEmpty()) {
          values.add("__unsupported__");
        } else {
          values.set(0, values.textNode("__unsupported__"));
        }
      }
      case MAP_VALUES_ONE_OF -> {
        ObjectNode values = (ObjectNode) node.path(field);
        String key = values.propertyStream().findFirst().orElseThrow().getKey();
        values.put(key, "__unsupported__");
      }
    }
  }

  private static Number below(Number bound) {
    return bound instanceof Integer
        ? bound.intValue() - 1
        : bound.doubleValue() - 0.01d;
  }

  private static Number above(Number bound) {
    return bound instanceof Integer
        ? bound.intValue() + 1
        : bound.doubleValue() + 0.01d;
  }

  private static void putNumber(ObjectNode node, String field, Number value) {
    if (value instanceof Integer integer) {
      node.put(field, integer);
    } else {
      node.put(field, value.doubleValue());
    }
  }

  private static void makeTooShort(ObjectNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isArray()) {
      ((ArrayNode) value).removeAll();
    } else {
      node.put(field, "");
    }
  }

  private static void makeTooLong(ObjectNode node, String field, int maximum) {
    JsonNode value = node.path(field);
    if (value.isArray()) {
      ArrayNode array = (ArrayNode) value;
      JsonNode seed = array.get(0);
      while (array.size() <= maximum) {
        array.add(seed.deepCopy());
      }
    } else {
      node.put(field, "x".repeat(maximum + 1));
    }
  }

  private static <T extends ConfigModel> T bind(
      JsonNode node, Class<T> type) {
    return LOADER.bindValue(node, type);
  }
}
