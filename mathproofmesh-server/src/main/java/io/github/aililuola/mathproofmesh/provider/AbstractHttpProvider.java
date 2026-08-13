package io.github.aililuola.mathproofmesh.provider;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceBinding;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceCall;
import io.github.aililuola.mathproofmesh.config.SecretValue;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

abstract class AbstractHttpProvider implements LLMClient {
  private static final int BUFFER_SIZE = 8192;

  private final String providerId;
  protected final String model;
  protected final URI baseUri;
  protected final SecretValue apiKey;
  protected final HttpTransport transport;
  protected final ProviderLimits limits;
  private final Duration timeout;
  private final Map<String, String> extraHeaders;
  private final LiveProviderPolicy livePolicy;
  private final Map<ProviderRequest, Map<String, Object>> progress =
      Collections.synchronizedMap(new IdentityHashMap<>());

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Construction validates and defensively copies every dependency before exposure; "
              + "all concrete provider subclasses are trusted and define no finalizer.")
  AbstractHttpProvider(
      String providerId,
      SecretValue apiKey,
      String model,
      URI baseUri,
      Duration timeout,
      Map<String, String> extraHeaders,
      HttpTransport transport,
      ProviderLimits limits,
      LiveProviderPolicy livePolicy) {
    this.providerId = requireText(providerId, "providerId");
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey").copy();
    this.model = requireText(model, "model");
    this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
    this.timeout = requirePositive(timeout, "timeout");
    this.extraHeaders =
        ProviderJson.cleanHeaders(
            extraHeaders == null ? Map.of() : extraHeaders);
    this.transport = Objects.requireNonNull(transport, "transport");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.livePolicy = Objects.requireNonNull(livePolicy, "livePolicy");
  }

  @Override
  public final String providerId() {
    return providerId;
  }

  @Override
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "Provider failures are a typed runtime contract; after recording the terminal trace "
              + "state this boundary must preserve the original failure for retry policy.")
  public final LLMResponse complete(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    URI endpoint = endpoint(request);
    livePolicy.authorize(endpoint, transport);
    if (request.cancelled().getAsBoolean()) {
      throw ProviderException.cancelled();
    }
    Map<String, String> headers = new LinkedHashMap<>(headers(request));
    for (Map.Entry<String, String> extra : extraHeaders.entrySet()) {
      String normalized = extra.getKey().toLowerCase(Locale.ROOT);
      if (isCredentialHeader(normalized)) {
        throw new IllegalArgumentException(
            "extra_headers cannot replace provider credential headers");
      }
      headers.put(extra.getKey(), extra.getValue());
    }
    headers.put("Content-Type", "application/json");
    headers.put(
        "Accept", request.streaming() ? "text/event-stream" : "application/json");
    byte[] body = ProviderJson.write(requestBody(request));
    HttpTransportRequest httpRequest =
        new HttpTransportRequest(endpoint, "POST", headers, body, timeout);
    ReasoningTraceCall traceCall = beginReasoningTrace(request);
    long started = System.nanoTime();
    try (HttpTransportResponse response = transport.send(httpRequest)) {
      if (response.statusCode() >= 400) {
        throw ProviderException.http(
            response.statusCode(), parseRetryAfter(response.firstHeader("Retry-After")));
      }
      String requestId = requestId(response);
      if (request.streaming()) {
        BoundedSseParser parser = new BoundedSseParser(limits);
        progress.put(request, Map.of("chunks", 0, "status", "streaming"));
        try {
          List<String> events =
              parser.parse(
                  response.body(),
                  request.cancelled(),
                  event -> observeStreamingEvent(event, traceCall));
          progress.put(
              request,
              Map.of(
                  "chunks",
                  events.size(),
                  "status",
                  "succeeded",
                  "first_chunk_latency_ms",
                  parser.firstChunkLatencyMs()));
          LLMResponse parsed =
              withFirstChunkLatency(
                  parseStream(events, requestId, elapsedMillis(started), request),
                  parser.firstChunkLatencyMs());
          finishTrace(traceCall, ReasoningTraceCall.Status.COMPLETED, null);
          return parsed;
        } catch (SseDisconnectException disconnect) {
          progress.put(
              request,
              Map.of(
                  "chunks",
                  disconnect.events().size(),
                  "status",
                  "failed",
                  "first_chunk_latency_ms",
                  parser.firstChunkLatencyMs()));
          throw interruptedStream(
              disconnect.events(), disconnect.getCause());
        }
      }
      byte[] responseBytes =
          readBounded(
              response.body(),
              limits.maxResponseBytes(),
              request.cancelled());
      JsonNode payload =
          ProviderJson.parse(new String(responseBytes, StandardCharsets.UTF_8));
      observeResponse(payload, traceCall);
      LLMResponse parsed =
          parseResponse(payload, requestId, elapsedMillis(started), request);
      finishTrace(traceCall, ReasoningTraceCall.Status.COMPLETED, null);
      return parsed;
    } catch (ProviderException exception) {
      finishTrace(
          traceCall,
          exception.kind() == ProviderErrorKind.CANCELLED
              ? ReasoningTraceCall.Status.CANCELLED
              : ReasoningTraceCall.Status.FAILED,
          exception.getClass().getSimpleName());
      throw exception;
    } catch (ContractValidationException exception) {
      finishTrace(traceCall, ReasoningTraceCall.Status.FAILED, exception.getClass().getSimpleName());
      throw ProviderException.protocol("invalid provider JSON response", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      finishTrace(traceCall, ReasoningTraceCall.Status.CANCELLED, exception.getClass().getSimpleName());
      throw ProviderException.cancelled();
    } catch (IOException exception) {
      finishTrace(traceCall, ReasoningTraceCall.Status.FAILED, exception.getClass().getSimpleName());
      throw ProviderException.network(exception);
    } catch (RuntimeException exception) {
      finishTrace(traceCall, ReasoningTraceCall.Status.FAILED, exception.getClass().getSimpleName());
      throw exception;
    }
  }

  @Override
  public void close() {
    apiKey.close();
  }

  protected abstract URI endpoint(ProviderRequest request);

  protected abstract Map<String, String> headers(ProviderRequest request);

  protected abstract JsonNode requestBody(ProviderRequest request);

  protected abstract LLMResponse parseResponse(
      JsonNode payload,
      String requestId,
      double latencyMs,
      ProviderRequest request);

  protected abstract LLMResponse parseStream(
      List<String> events,
      String requestId,
      double latencyMs,
      ProviderRequest request);

  protected ProviderException interruptedStream(
      List<String> events, Throwable cause) {
    return ProviderException.network(cause);
  }

  protected void observeStreamingEvent(String event, ReasoningTraceCall traceCall) {}

  protected void observeResponse(JsonNode payload, ReasoningTraceCall traceCall) {}

  public Map<String, Object> progressSnapshotFor(ProviderRequest request) {
    synchronized (progress) {
      Map<String, Object> snapshot = progress.get(request);
      return snapshot == null ? Map.of() : Map.copyOf(snapshot);
    }
  }

  public void clearProgressFor(ProviderRequest request) {
    progress.remove(request);
  }

  protected final JsonNode getJson(
      URI endpoint, Map<String, String> requestHeaders) {
    livePolicy.authorize(endpoint, transport);
    Map<String, String> headers =
        new LinkedHashMap<>(
            Objects.requireNonNull(requestHeaders, "requestHeaders"));
    for (Map.Entry<String, String> extra : extraHeaders.entrySet()) {
      if (isCredentialHeader(extra.getKey().toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException(
            "extra_headers cannot replace provider credential headers");
      }
      headers.put(extra.getKey(), extra.getValue());
    }
    headers.put("Accept", "application/json");
    HttpTransportRequest request =
        new HttpTransportRequest(endpoint, "GET", headers, new byte[0], timeout);
    try (HttpTransportResponse response = transport.send(request)) {
      if (response.statusCode() >= 400) {
        throw ProviderException.http(
            response.statusCode(),
            parseRetryAfter(response.firstHeader("Retry-After")));
      }
      byte[] bytes =
          readBounded(
              response.body(), limits.maxResponseBytes(), () -> false);
      return ProviderJson.parse(new String(bytes, StandardCharsets.UTF_8));
    } catch (ProviderException exception) {
      throw exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw ProviderException.cancelled();
    } catch (IOException exception) {
      throw ProviderException.network(exception);
    }
  }

  protected String secretText() {
    return apiKey.use(characters -> new String(characters));
  }

  protected static URI appendPath(URI base, String suffix) {
    String normalizedBase = base.toString().replaceAll("/+$", "");
    String normalizedSuffix = suffix.startsWith("/") ? suffix : "/" + suffix;
    return URI.create(normalizedBase + normalizedSuffix);
  }

  private static byte[] readBounded(
      java.io.InputStream input,
      long maximumBytes,
      java.util.function.BooleanSupplier cancelled)
      throws IOException {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[BUFFER_SIZE];
      while (true) {
        if (cancelled.getAsBoolean()
            || Thread.currentThread().isInterrupted()) {
          throw ProviderException.cancelled();
        }
        int count = input.read(buffer);
        if (count < 0) {
          return output.toByteArray();
        }
        if (count == 0) {
          continue;
        }
        if (output.size() > maximumBytes - count) {
          throw ProviderException.tooLarge(maximumBytes);
        }
        output.write(buffer, 0, count);
      }
    }
  }

  private static String requestId(HttpTransportResponse response) {
    String value = response.firstHeader("x-request-id");
    if (value == null) {
      value = response.firstHeader("request-id");
    }
    return value;
  }

  private static boolean isCredentialHeader(String normalizedName) {
    return normalizedName.equals("authorization")
        || normalizedName.equals("x-api-key")
        || normalizedName.equals("x-goog-api-key");
  }

  private static LLMResponse withFirstChunkLatency(
      LLMResponse response, double firstChunkLatencyMs) {
    JsonNode metadata = response.metadata();
    if (metadata
        instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
      object.withObject("stream")
          .put("first_chunk_latency_ms", firstChunkLatencyMs);
    }
    return new LLMResponse(
        response.text(),
        response.model(),
        response.provider(),
        response.inputTokens(),
        response.outputTokens(),
        response.latencyMs(),
        response.requestId(),
        response.finishReason(),
        response.streaming(),
        metadata);
  }

  private static Duration parseRetryAfter(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      double seconds = Double.parseDouble(value.strip());
      return Duration.ofMillis(Math.max(0L, Math.round(seconds * 1000.0d)));
    } catch (NumberFormatException ignored) {
      try {
        ZonedDateTime when =
            ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
        long millis =
            ZonedDateTime.now(when.getZone()).until(when, ChronoUnit.MILLIS);
        return Duration.ofMillis(Math.max(0L, millis));
      } catch (DateTimeParseException ignoredDate) {
        return null;
      }
    }
  }

  private static double elapsedMillis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000.0d;
  }

  private static ReasoningTraceCall beginReasoningTrace(ProviderRequest request) {
    return ReasoningTraceBinding.current()
        .map(
            binding ->
                binding
                    .store()
                    .beginCall(
                        binding.taskId(),
                        binding.agentId(),
                        binding.stage(),
                        binding.providerCallId(),
                        Boolean.TRUE.equals(request.thinkingEnabled()),
                        Boolean.TRUE.equals(request.thinkingEnabled())
                            ? request.reasoningEffort()
                            : null))
        .orElse(null);
  }

  private static void finishTrace(
      ReasoningTraceCall traceCall, ReasoningTraceCall.Status status, String errorType) {
    if (traceCall != null) {
      traceCall.finish(status, errorType);
    }
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  private static Duration requirePositive(Duration value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(label + " must be positive");
    }
    return value;
  }
}
