package io.github.aililuola.mathproofmesh.desktop.benchmark;

import io.github.aililuola.mathproofmesh.config.EnvironmentLookup;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** In-memory-only benchmark credentials and explicit real-provider authorization. */
public final class BenchmarkSecretSet implements AutoCloseable {
  public static final String COST_CAP_ENV = "BENCHMARK_GLOBAL_COST_CAP_USD";
  public static final String REAL_PROVIDER_ENV = "BENCHMARK_ALLOW_REAL_PROVIDER";

  private final Map<String, char[]> credentialsByEnvironment;
  private final BigDecimal globalCostCapUsd;
  private boolean closed;

  private BenchmarkSecretSet(
      Map<String, char[]> credentialsByEnvironment, BigDecimal globalCostCapUsd) {
    this.credentialsByEnvironment = credentialsByEnvironment;
    this.globalCostCapUsd = globalCostCapUsd;
  }

  public static BenchmarkSecretSet load(EnvironmentLookup environment) {
    Objects.requireNonNull(environment, "environment");
    if (!"true".equals(environment.lookup(REAL_PROVIDER_ENV))) {
      throw new IllegalStateException("real benchmark provider is not explicitly enabled");
    }
    BigDecimal cap = parseCap(environment.lookup(COST_CAP_ENV));
    Map<String, char[]> credentials = new LinkedHashMap<>();
    for (String label : OlympiadBenchmarkPlan.KEY_LABELS) {
      String name = OlympiadBenchmarkPlan.keyEnvironmentName(label);
      String value = environment.lookup(name);
      if (value == null || value.isBlank()) {
        wipe(credentials.values());
        throw new IllegalStateException("all five benchmark credential variables are required");
      }
      char[] chars = value.toCharArray();
      if (contains(credentials.values(), chars)) {
        Arrays.fill(chars, '\0');
        wipe(credentials.values());
        throw new IllegalStateException("benchmark credentials must be pairwise distinct");
      }
      credentials.put(name, chars);
    }
    return new BenchmarkSecretSet(credentials, cap);
  }

  public BigDecimal globalCostCapUsd() {
    ensureOpen();
    return globalCostCapUsd;
  }

  public String credential(String environmentName) {
    ensureOpen();
    char[] value = credentialsByEnvironment.get(environmentName);
    if (value == null) {
      return null;
    }
    return new String(value);
  }

  public List<String> transientValues() {
    ensureOpen();
    return credentialsByEnvironment.values().stream().map(String::new).toList();
  }

  public Map<String, String> redactedStatuses() {
    ensureOpen();
    Map<String, String> result = new LinkedHashMap<>();
    OlympiadBenchmarkPlan.KEY_LABELS.forEach(label -> result.put(label, "configured-in-memory"));
    return Map.copyOf(result);
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    wipe(credentialsByEnvironment.values());
    credentialsByEnvironment.clear();
    closed = true;
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("benchmark secret set is closed");
    }
  }

  private static BigDecimal parseCap(String value) {
    try {
      BigDecimal cap = new BigDecimal(Objects.requireNonNull(value, COST_CAP_ENV)).stripTrailingZeros();
      if (cap.signum() <= 0) {
        throw new IllegalArgumentException("benchmark cost cap must be positive");
      }
      return cap;
    } catch (NumberFormatException | NullPointerException exception) {
      throw new IllegalStateException("a valid benchmark cost cap is required", exception);
    }
  }

  private static boolean contains(Iterable<char[]> values, char[] candidate) {
    for (char[] value : values) {
      if (Arrays.equals(value, candidate)) {
        return true;
      }
    }
    return false;
  }

  private static void wipe(Iterable<char[]> values) {
    values.forEach(value -> Arrays.fill(value, '\0'));
  }
}
