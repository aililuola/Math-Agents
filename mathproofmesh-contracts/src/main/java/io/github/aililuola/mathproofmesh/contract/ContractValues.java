package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.Objects;

public final class ContractValues {
  private ContractValues() {}

  public static <T> T required(String name, T value) {
    if (value == null) {
      throw new ContractValidationException(name + " is required");
    }
    return value;
  }

  public static ObjectNode requiredObject(String name, ObjectNode value) {
    if (value == null) {
      throw new ContractValidationException(name + " is required");
    }
    return value.deepCopy();
  }

  public static ObjectNode objectOrEmpty(ObjectNode value) {
    return value == null ? JsonNodeFactory.instance.objectNode() : value.deepCopy();
  }

  public static ObjectNode copyObject(ObjectNode value) {
    return value == null ? null : value.deepCopy();
  }

  public static JsonNode copyJson(JsonNode value) {
    return value == null ? null : value.deepCopy();
  }

  public static void minimum(String name, Number value, double minimum) {
    if (value != null && value.doubleValue() < minimum) {
      throw new ContractValidationException(name + " must be >= " + minimum);
    }
  }

  public static void maximum(String name, Number value, double maximum) {
    if (value != null && value.doubleValue() > maximum) {
      throw new ContractValidationException(name + " must be <= " + maximum);
    }
  }

  public static void exclusiveMinimum(String name, Number value, double minimum) {
    if (value != null && value.doubleValue() <= minimum) {
      throw new ContractValidationException(name + " must be > " + minimum);
    }
  }

  public static void exclusiveMaximum(String name, Number value, double maximum) {
    if (value != null && value.doubleValue() >= maximum) {
      throw new ContractValidationException(name + " must be < " + maximum);
    }
  }

  public static void minimumLength(String name, String value, int minimum) {
    if (value != null && value.length() < minimum) {
      throw new ContractValidationException(name + " is shorter than " + minimum);
    }
  }

  public static void maximumLength(String name, String value, int maximum) {
    if (value != null && value.length() > maximum) {
      throw new ContractValidationException(name + " is longer than " + maximum);
    }
  }

  public static void minimumSize(String name, Collection<?> value, int minimum) {
    if (value != null && value.size() < minimum) {
      throw new ContractValidationException(name + " has fewer than " + minimum + " items");
    }
  }

  public static void maximumSize(String name, Collection<?> value, int maximum) {
    if (value != null && value.size() > maximum) {
      throw new ContractValidationException(name + " has more than " + maximum + " items");
    }
  }

  public static void oneOf(String name, String value, String... allowed) {
    if (value == null) {
      return;
    }
    for (String candidate : allowed) {
      if (candidate.equals(value)) {
        return;
      }
    }
    throw new ContractValidationException(name + " has an unsupported literal: " + value);
  }

  public static <T> void constant(String name, T value, T expected) {
    if (!Objects.equals(value, expected)) {
      throw new ContractValidationException(name + " must equal " + expected);
    }
  }
}
