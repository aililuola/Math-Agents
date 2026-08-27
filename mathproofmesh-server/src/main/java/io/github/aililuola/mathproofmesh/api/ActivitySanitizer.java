package io.github.aililuola.mathproofmesh.api;

import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ActivitySanitizer {
  private static final Pattern SK_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{10,}\\b");
  private static final Pattern BEARER =
      Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}");
  private static final Pattern API_KEY =
      Pattern.compile("(?i)(api[_ -]?key\\s*[:=]\\s*)[^\\s,;]+");
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\r\\n\\t]]");
  private static final Set<String> SECRET_KEYS =
      Set.of(
          "authorization",
          "api_key",
          "apikey",
          "token",
          "secret",
          "credential",
          "password",
          "prompt",
          "raw_reasoning",
          "reasoning_content",
          "chain_of_thought");

  private ActivitySanitizer() {}

  static String text(Object value, int limit) {
    String result = value == null ? "" : String.valueOf(value);
    result = CONTROL.matcher(result).replaceAll(" ");
    result = result.replaceAll("\\s+", " ").trim();
    result = redactSecretsPreservingWhitespace(result);
    if (result.length() > limit) {
      result = result.substring(0, Math.max(0, limit - 3)).stripTrailing() + "...";
    }
    return result;
  }

  static String redactSecretsPreservingWhitespace(String value) {
    String result = CONTROL.matcher(value == null ? "" : value).replaceAll(" ");
    result = SK_KEY.matcher(result).replaceAll("[REDACTED]");
    result = BEARER.matcher(result).replaceAll("[REDACTED]");
    result = API_KEY.matcher(result).replaceAll("$1[REDACTED]");
    return result;
  }

  static String identifier(Object value, int limit) {
    String result = text(value, limit);
    if (result.isBlank()) {
      throw new IllegalArgumentException("activity identifier must not be blank");
    }
    return result;
  }

  static String nullableIdentifier(Object value, int limit) {
    if (value == null || String.valueOf(value).isBlank()) {
      return null;
    }
    return identifier(value, limit);
  }

  static Map<String, Object> metrics(Map<String, ?> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Object sanitized = value(source, 0);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) sanitized;
    return Collections.unmodifiableMap(new LinkedHashMap<>(result));
  }

  private static Object value(Object source, int depth) {
    if (source == null || source instanceof Boolean || source instanceof Integer
        || source instanceof Long || source instanceof Short || source instanceof Byte) {
      return source;
    }
    if (source instanceof Number number) {
      double value = number.doubleValue();
      return Double.isFinite(value) ? number : text(number, 40);
    }
    if (depth >= 5) {
      return "[TRUNCATED]";
    }
    if (source instanceof CharSequence || source instanceof Character || source instanceof Path) {
      return text(source, 400);
    }
    if (source instanceof Map<?, ?> sourceMap) {
      Map<String, Object> result = new LinkedHashMap<>();
      int index = 0;
      for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
        if (index++ >= 50) {
          result.put("_truncated", true);
          break;
        }
        String key = text(entry.getKey(), 100);
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        result.put(
            key,
            SECRET_KEYS.contains(normalized)
                ? "[REDACTED]"
                : value(entry.getValue(), depth + 1));
      }
      return result;
    }
    if (source instanceof Iterable<?> iterable) {
      List<Object> result = new ArrayList<>();
      for (Object item : iterable) {
        if (result.size() >= 50) {
          result.add("[TRUNCATED]");
          break;
        }
        result.add(value(item, depth + 1));
      }
      return List.copyOf(result);
    }
    if (source.getClass().isArray()) {
      List<Object> result = new ArrayList<>();
      int length = Math.min(Array.getLength(source), 50);
      for (int index = 0; index < length; index++) {
        result.add(value(Array.get(source, index), depth + 1));
      }
      if (Array.getLength(source) > length) {
        result.add("[TRUNCATED]");
      }
      return List.copyOf(result);
    }
    return text(source, 400);
  }
}
