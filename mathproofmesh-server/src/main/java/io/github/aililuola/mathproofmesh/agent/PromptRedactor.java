package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.config.SecretValue;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PromptRedactor {
  private static final Pattern BEARER =
      Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");
  private static final Pattern PROVIDER_KEY =
      Pattern.compile("(?i)\\b(?:sk|api)[-_][A-Za-z0-9_-]{8,}");
  private static final Pattern ASSIGNMENT =
      Pattern.compile(
          "(?i)(api[_-]?key|token|secret)\\s*[:=]\\s*[^\\s,;]{4,}");

  private final List<String> explicitSecrets;

  public PromptRedactor(List<String> explicitSecrets) {
    this.explicitSecrets =
        (explicitSecrets == null ? List.<String>of() : explicitSecrets).stream()
            .filter(Objects::nonNull)
            .filter(value -> !value.isEmpty())
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
  }

  public String redact(String value) {
    String redacted = Objects.requireNonNull(value, "value");
    for (String secret : explicitSecrets) {
      redacted = redacted.replace(secret, SecretValue.REDACTED);
    }
    redacted = BEARER.matcher(redacted).replaceAll("Bearer " + SecretValue.REDACTED);
    redacted = PROVIDER_KEY.matcher(redacted).replaceAll(SecretValue.REDACTED);
    return ASSIGNMENT.matcher(redacted).replaceAll("$1=" + SecretValue.REDACTED);
  }

  public boolean containsSecret(String value) {
    return !redact(value).equals(value);
  }
}
