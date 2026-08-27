package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

public final class SecretValue implements AutoCloseable {
  public static final String REDACTED = "[REDACTED]";

  private char[] value;
  private boolean destroyed;

  private SecretValue(char[] value) {
    this.value = Arrays.copyOf(value, value.length);
  }

  @JsonCreator
  public static SecretValue of(String value) {
    Objects.requireNonNull(value, "value");
    return new SecretValue(value.toCharArray());
  }

  public SecretValue copy() {
    ensureAvailable();
    return new SecretValue(value);
  }

  public <T> T use(Function<char[], T> operation) {
    Objects.requireNonNull(operation, "operation");
    ensureAvailable();
    char[] temporary = Arrays.copyOf(value, value.length);
    try {
      return operation.apply(temporary);
    } finally {
      Arrays.fill(temporary, '\0');
    }
  }

  public boolean isDestroyed() {
    return destroyed;
  }

  @JsonValue
  public String redacted() {
    return REDACTED;
  }

  @Override
  public String toString() {
    return REDACTED;
  }

  @Override
  public void close() {
    if (!destroyed) {
      Arrays.fill(value, '\0');
      value = new char[0];
      destroyed = true;
    }
  }

  private void ensureAvailable() {
    if (destroyed) {
      throw new IllegalStateException("secret value has been destroyed");
    }
  }
}
