package io.github.aililuola.mathproofmesh.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Conservative representation repair: make invalid string escapes explicit,
 * extract one object, and optionally remove a single result/data wrapper. No
 * decoded mathematical leaf value is changed.
 */
public final class BoundedJsonRepairer {
  private final int maximumCharacters;

  public BoundedJsonRepairer(int maximumCharacters) {
    if (maximumCharacters < 2) {
      throw new IllegalArgumentException("maximumCharacters is too small");
    }
    this.maximumCharacters = maximumCharacters;
  }

  public String repair(String raw) {
    if (raw == null || raw.length() > maximumCharacters) {
      throw new StructuredOutputError("JSON repair input exceeds its bound");
    }
    String extracted =
        JsonObjectExtractor.firstBalancedObject(escapeInvalidStringEscapes(raw));
    JsonNode original = ContractObjectMapper.parseTree(extracted);
    JsonNode repaired = unwrap(original);
    if (!textLeaves(original).containsAll(textLeaves(repaired))) {
      throw new StructuredOutputError(
          "JSON repair attempted to change mathematical text");
    }
    return ContractObjectMapper.write(repaired);
  }

  public String repairPrompt(String raw, Class<?> responseType) {
    return PromptCatalog.JSON_REPAIR_SYSTEM
        + "\n\nRESPONSE CONTRACT: "
        + responseType.getName()
        + "\nPUBLIC OUTPUT TO REPAIR:\n"
        + raw;
  }

  private static JsonNode unwrap(JsonNode original) {
    if (original.isObject() && original.size() == 1) {
      for (String key : List.of("result", "data", "response")) {
        JsonNode child = original.get(key);
        if (child != null && child.isObject()) {
          return child;
        }
      }
    }
    return original;
  }

  private static String escapeInvalidStringEscapes(String raw) {
    StringBuilder repaired = new StringBuilder(raw.length());
    boolean inString = false;
    for (int index = 0; index < raw.length(); index++) {
      char character = raw.charAt(index);
      if (!inString) {
        repaired.append(character);
        if (character == '"') {
          inString = true;
        }
        continue;
      }
      if (character == '"') {
        repaired.append(character);
        inString = false;
        continue;
      }
      if (character != '\\') {
        appendEscapedControlCharacter(repaired, character);
        continue;
      }

      if (index + 1 >= raw.length()) {
        repaired.append("\\\\");
        continue;
      }
      char escape = raw.charAt(index + 1);
      if (isSimpleJsonEscape(escape)) {
        repaired.append(character).append(escape);
        index++;
        continue;
      }
      if (escape == 'u' && hasFourHexDigits(raw, index + 2)) {
        repaired.append(raw, index, index + 6);
        index += 5;
        continue;
      }

      // A model often writes LaTeX or set difference as \gcd or Z \ C.
      // Doubling only the representation backslash preserves the decoded text.
      repaired.append("\\\\");
    }
    return repaired.toString();
  }

  private static boolean isSimpleJsonEscape(char value) {
    return value == '"'
        || value == '\\'
        || value == '/'
        || value == 'b'
        || value == 'f'
        || value == 'n'
        || value == 'r'
        || value == 't';
  }

  private static boolean hasFourHexDigits(String value, int start) {
    if (start + 4 > value.length()) {
      return false;
    }
    for (int index = start; index < start + 4; index++) {
      char character = value.charAt(index);
      if (!((character >= '0' && character <= '9')
          || (character >= 'a' && character <= 'f')
          || (character >= 'A' && character <= 'F'))) {
        return false;
      }
    }
    return true;
  }

  private static void appendEscapedControlCharacter(StringBuilder target, char value) {
    switch (value) {
      case '\b' -> target.append("\\b");
      case '\f' -> target.append("\\f");
      case '\n' -> target.append("\\n");
      case '\r' -> target.append("\\r");
      case '\t' -> target.append("\\t");
      default -> {
        if (value < 0x20) {
          String hex = Integer.toHexString(value);
          target.append("\\u").append("0000", 0, 4 - hex.length()).append(hex);
        } else {
          target.append(value);
        }
      }
    }
  }

  private static List<String> textLeaves(JsonNode node) {
    List<String> result = new ArrayList<>();
    collect(node, result);
    return result;
  }

  private static void collect(JsonNode node, List<String> target) {
    if (node.isTextual()) {
      target.add(node.textValue());
    } else if (node.isContainerNode()) {
      node.elements().forEachRemaining(child -> collect(child, target));
    }
  }
}
