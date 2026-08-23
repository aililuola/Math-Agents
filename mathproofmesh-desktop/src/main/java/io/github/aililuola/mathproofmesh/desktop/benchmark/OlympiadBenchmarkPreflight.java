package io.github.aililuola.mathproofmesh.desktop.benchmark;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.provider.HttpTransport;
import io.github.aililuola.mathproofmesh.provider.HttpTransportRequest;
import io.github.aililuola.mathproofmesh.provider.HttpTransportResponse;
import io.github.aililuola.mathproofmesh.provider.JdkHttpTransport;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Performs one redacted, non-scored DeepSeek connectivity check per isolated benchmark key. */
public final class OlympiadBenchmarkPreflight {
  private static final URI MODELS_ENDPOINT = URI.create("https://api.deepseek.com/models");

  private OlympiadBenchmarkPreflight() {}

  public static Result execute(Path outputDirectory, BenchmarkSecretSet secrets) {
    return execute(outputDirectory, secrets, ignored -> new JdkHttpTransport(), Clock.systemUTC());
  }

  static Result execute(
      Path outputDirectory,
      BenchmarkSecretSet secrets,
      Function<String, HttpTransport> transports,
      Clock clock) {
    Path output =
        Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
    Objects.requireNonNull(secrets, "secrets");
    Objects.requireNonNull(transports, "transports");
    Objects.requireNonNull(clock, "clock");
    List<Check> checks = new ArrayList<>();
    for (String label : OlympiadBenchmarkPlan.KEY_LABELS) {
      String environmentName = OlympiadBenchmarkPlan.keyEnvironmentName(label);
      HttpTransport transport =
          Objects.requireNonNull(transports.apply(label), "preflight transport");
      int status = 0;
      String outcome = "TRANSPORT_ERROR";
      try (HttpTransportResponse response =
          transport.send(
              new HttpTransportRequest(
                  MODELS_ENDPOINT,
                  "GET",
                  Map.of("Authorization", "Bearer " + secrets.credential(environmentName)),
                  new byte[0],
                  Duration.ofSeconds(30)))) {
        status = response.statusCode();
        outcome = status >= 200 && status < 300 ? "PASS" : "HTTP_REJECTED";
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        outcome = "INTERRUPTED";
      } catch (IOException | RuntimeException exception) {
        outcome = "TRANSPORT_ERROR";
      }
      checks.add(new Check(label, status, outcome, clock.instant().toString()));
    }
    Result result = new Result(List.copyOf(checks));
    write(output, result);
    return result;
  }

  private static void write(Path output, Result result) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("endpoint_host", MODELS_ENDPOINT.getHost());
    document.put("endpoint_path", MODELS_ENDPOINT.getPath());
    document.put("checks", result.checks());
    document.put("result", result.passed() ? "PASS" : "FAIL");
    try {
      Files.createDirectories(output);
      Files.writeString(
          output.resolve("five-key-connectivity.json"),
          ContractObjectMapper.write(document) + "\n",
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark preflight result could not be written", exception);
    }
  }

  public record Check(String keyLabel, int statusCode, String outcome, String observedAt) {
    public Check {
      if (!OlympiadBenchmarkPlan.KEY_LABELS.contains(keyLabel)) {
        throw new IllegalArgumentException("unknown benchmark key label");
      }
      if (statusCode < 0 || statusCode > 999) {
        throw new IllegalArgumentException("statusCode is outside the supported range");
      }
      outcome = Objects.requireNonNull(outcome, "outcome");
      observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    public boolean passed() {
      return "PASS".equals(outcome) && statusCode >= 200 && statusCode < 300;
    }
  }

  public record Result(List<Check> checks) {
    public Result {
      checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
      if (checks.size() != OlympiadBenchmarkPlan.KEY_LABELS.size()) {
        throw new IllegalArgumentException("preflight must check all five keys exactly once");
      }
    }

    @Override
    public List<Check> checks() {
      return List.copyOf(checks);
    }

    public boolean passed() {
      return checks.stream().allMatch(Check::passed);
    }
  }
}
