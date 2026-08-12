package io.github.aililuola.mathproofmesh.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ConfigValidation {
  private ConfigValidation() {}

  static <T> T required(String field, T value) {
    if (value == null) {
      throw invalid(field, "is required");
    }
    return value;
  }

  static String trim(String value) {
    return value == null ? null : value.strip();
  }

  static <T> List<T> immutableList(String field, List<T> values) {
    if (values == null) {
      return null;
    }
    for (T value : values) {
      if (value == null) {
        throw invalid(field, "cannot contain null values");
      }
    }
    return List.copyOf(values);
  }

  static List<String> trimStrings(String field, List<String> values) {
    if (values == null) {
      return null;
    }
    List<String> normalized = new ArrayList<>(values.size());
    for (String value : values) {
      if (value == null) {
        throw invalid(field, "cannot contain null values");
      }
      normalized.add(value.strip());
    }
    return List.copyOf(normalized);
  }

  static <K, V> Map<K, V> immutableMap(String field, Map<K, V> values) {
    if (values == null) {
      return null;
    }
    for (Map.Entry<K, V> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw invalid(field, "cannot contain null keys or values");
      }
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  static Map<String, String> trimStringMap(String field, Map<String, String> values) {
    if (values == null) {
      return null;
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw invalid(field, "cannot contain null keys or values");
      }
      normalized.put(entry.getKey().strip(), entry.getValue().strip());
    }
    return Collections.unmodifiableMap(normalized);
  }

  static List<Integer> sortedDistinct(String field, List<Integer> values) {
    if (values == null) {
      return null;
    }
    for (Integer value : values) {
      if (value == null) {
        throw invalid(field, "cannot contain null values");
      }
    }
    return List.copyOf(new java.util.TreeSet<>(values));
  }

  static void minimum(String field, Number value, Number minimum) {
    if (value != null && Double.compare(value.doubleValue(), minimum.doubleValue()) < 0) {
      throw invalid(field, "must be at least " + minimum);
    }
  }

  static void exclusiveMinimum(String field, Number value, Number minimum) {
    if (value != null && Double.compare(value.doubleValue(), minimum.doubleValue()) <= 0) {
      throw invalid(field, "must be greater than " + minimum);
    }
  }

  static void maximum(String field, Number value, Number maximum) {
    if (value != null && Double.compare(value.doubleValue(), maximum.doubleValue()) > 0) {
      throw invalid(field, "must be at most " + maximum);
    }
  }

  static void minimumLength(String field, Object value, int minimum) {
    if (value != null && size(field, value) < minimum) {
      throw invalid(field, "must contain at least " + minimum + " item(s)");
    }
  }

  static void maximumLength(String field, Object value, int maximum) {
    if (value != null && size(field, value) > maximum) {
      throw invalid(field, "must contain at most " + maximum + " item(s)");
    }
  }

  static void oneOf(String field, Object value, Object... allowed) {
    if (value == null) {
      return;
    }
    for (Object candidate : allowed) {
      if (Objects.equals(value, candidate)) {
        return;
      }
    }
    throw invalid(field, "contains an unsupported value");
  }

  static void itemsOneOf(String field, List<?> values, Object... allowed) {
    if (values == null) {
      return;
    }
    for (Object value : values) {
      oneOf(field, value, allowed);
    }
  }

  static void mapValuesOneOf(String field, Map<?, ?> values, Object... allowed) {
    if (values == null) {
      return;
    }
    for (Object value : values.values()) {
      oneOf(field, value, allowed);
    }
  }

  static void strictlyIncreasing(String field, List<Integer> values) {
    if (values == null) {
      return;
    }
    Integer previous = null;
    for (Integer value : values) {
      if (value == null || previous != null && value <= previous) {
        throw invalid(field, "must be strictly increasing");
      }
      previous = value;
    }
  }

  static void unique(String field, List<String> values) {
    if (values != null && new LinkedHashSet<>(values).size() != values.size()) {
      throw invalid(field, "cannot contain duplicates");
    }
  }

  private static int size(String field, Object value) {
    if (value instanceof CharSequence sequence) {
      return sequence.length();
    }
    if (value instanceof Collection<?> collection) {
      return collection.size();
    }
    if (value instanceof Map<?, ?> map) {
      return map.size();
    }
    throw invalid(field, "does not support a length constraint");
  }

  private static ConfigValidationException invalid(String field, String detail) {
    return new ConfigValidationException(field + " " + detail);
  }
}
