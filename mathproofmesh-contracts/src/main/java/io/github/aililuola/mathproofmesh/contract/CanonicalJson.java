package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class CanonicalJson {
  private static final Comparator<String> CODE_POINT_ORDER = CanonicalJson::compareCodePoints;

  private CanonicalJson() {}

  public static String canonicalize(Object value) {
    JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : ContractObjectMapper.toTree(value);
    StringBuilder output = new StringBuilder();
    appendNode(output, node);
    return output.toString();
  }

  public static String stableHash(Object value) {
    byte[] bytes =
        value instanceof String stringValue
            ? stringValue.getBytes(StandardCharsets.UTF_8)
            : canonicalize(value).getBytes(StandardCharsets.UTF_8);
    try {
      return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }

  static Comparator<String> unicodeCodePointOrder() {
    return CODE_POINT_ORDER;
  }

  private static void appendNode(StringBuilder output, JsonNode node) {
    if (node == null || node.isNull()) {
      output.append("null");
    } else if (node.isObject()) {
      appendObject(output, node);
    } else if (node.isArray()) {
      appendArray(output, node);
    } else if (node.isTextual()) {
      appendQuoted(output, node.textValue());
    } else if (node.isBoolean()) {
      output.append(node.booleanValue());
    } else if (node.isIntegralNumber()) {
      output.append(node.bigIntegerValue());
    } else if (node.isFloatingPointNumber()) {
      output.append(pythonFloat(node.doubleValue()));
    } else {
      throw new ContractValidationException("unsupported JSON node type: " + node.getNodeType());
    }
  }

  private static void appendObject(StringBuilder output, JsonNode node) {
    List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
    entries.addAll(node.properties());
    entries.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));
    output.append('{');
    for (int index = 0; index < entries.size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      Map.Entry<String, JsonNode> entry = entries.get(index);
      appendQuoted(output, entry.getKey());
      output.append(':');
      appendNode(output, entry.getValue());
    }
    output.append('}');
  }

  private static void appendArray(StringBuilder output, JsonNode node) {
    output.append('[');
    for (int index = 0; index < node.size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      appendNode(output, node.get(index));
    }
    output.append(']');
  }

  private static void appendQuoted(StringBuilder output, String value) {
    output.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> output.append("\\\"");
        case '\\' -> output.append("\\\\");
        case '\b' -> output.append("\\b");
        case '\f' -> output.append("\\f");
        case '\n' -> output.append("\\n");
        case '\r' -> output.append("\\r");
        case '\t' -> output.append("\\t");
        default -> {
          if (character < 0x20) {
            output.append(String.format("\\u%04x", (int) character));
          } else {
            output.append(character);
          }
        }
      }
    }
    output.append('"');
  }

  private static String pythonFloat(double value) {
    if (!Double.isFinite(value)) {
      throw new ContractValidationException("canonical JSON does not allow non-finite numbers");
    }
    if (Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0.0d)) {
      return "-0.0";
    }
    double absolute = Math.abs(value);
    if (absolute == 0.0d || (absolute >= 1.0e-4d && absolute < 1.0e16d)) {
      String plain = BigDecimal.valueOf(value).toPlainString();
      return plain.indexOf('.') >= 0 ? plain : plain + ".0";
    }
    String javaValue = Double.toString(value);
    int marker = Math.max(javaValue.indexOf('E'), javaValue.indexOf('e'));
    if (marker < 0) {
      return javaValue;
    }
    String mantissa = javaValue.substring(0, marker);
    if (mantissa.endsWith(".0")) {
      mantissa = mantissa.substring(0, mantissa.length() - 2);
    }
    int exponent = Integer.parseInt(javaValue.substring(marker + 1));
    String sign = exponent >= 0 ? "+" : "-";
    int magnitude = Math.abs(exponent);
    String digits = magnitude < 10 ? "0" + magnitude : Integer.toString(magnitude);
    return mantissa + "e" + sign + digits;
  }

  private static int compareCodePoints(String left, String right) {
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.length() && rightIndex < right.length()) {
      int leftCodePoint = left.codePointAt(leftIndex);
      int rightCodePoint = right.codePointAt(rightIndex);
      if (leftCodePoint != rightCodePoint) {
        return Integer.compare(leftCodePoint, rightCodePoint);
      }
      leftIndex += Character.charCount(leftCodePoint);
      rightIndex += Character.charCount(rightCodePoint);
    }
    return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
  }

  private static String toHex(byte[] bytes) {
    StringBuilder output = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      output.append(Character.forDigit((value >>> 4) & 0x0f, 16));
      output.append(Character.forDigit(value & 0x0f, 16));
    }
    return output.toString();
  }
}
