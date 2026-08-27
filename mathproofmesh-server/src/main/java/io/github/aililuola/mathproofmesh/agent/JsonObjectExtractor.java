package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;

public final class JsonObjectExtractor {
  private JsonObjectExtractor() {}

  public static String firstBalancedObject(String text) {
    if (text == null) {
      throw new StructuredOutputError("structured output is null");
    }
    String cleaned = stripFence(text.strip());
    int start = cleaned.indexOf('{');
    if (start < 0) {
      throw new StructuredOutputError("no JSON object found");
    }
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int index = start; index < cleaned.length(); index++) {
      char character = cleaned.charAt(index);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (character == '\\') {
          escaped = true;
        } else if (character == '"') {
          inString = false;
        }
        continue;
      }
      if (character == '"') {
        inString = true;
      } else if (character == '{') {
        depth++;
      } else if (character == '}') {
        depth--;
        if (depth == 0) {
          String candidate = cleaned.substring(start, index + 1);
          if (!ContractObjectMapper.parseTree(candidate).isObject()) {
            throw new StructuredOutputError(
                "structured output must be a JSON object");
          }
          return candidate;
        }
      }
    }
    throw new StructuredOutputError("unterminated JSON object");
  }

  private static String stripFence(String text) {
    String result = text;
    if (result.startsWith("```")) {
      int newline = result.indexOf('\n');
      if (newline >= 0) {
        result = result.substring(newline + 1);
      }
    }
    if (result.endsWith("```")) {
      result = result.substring(0, result.length() - 3);
    }
    return result.strip();
  }
}
