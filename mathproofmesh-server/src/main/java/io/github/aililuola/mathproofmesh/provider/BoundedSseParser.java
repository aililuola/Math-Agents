package io.github.aililuola.mathproofmesh.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Strictly bounded SSE parser with first-byte and between-chunk timeouts. */
public final class BoundedSseParser {
  private static final int BUFFER_SIZE = 4096;

  private final long maximumBytes;
  private final Duration firstChunkTimeout;
  private final Duration idleTimeout;
  private double firstChunkLatencyMs;

  public BoundedSseParser(ProviderLimits limits) {
    Objects.requireNonNull(limits, "limits");
    this.maximumBytes = limits.maxResponseBytes();
    this.firstChunkTimeout = limits.firstChunkTimeout();
    this.idleTimeout = limits.idleTimeout();
  }

  public List<String> parse(InputStream input, BooleanSupplier cancelled) {
    return parse(input, cancelled, null);
  }

  public List<String> parse(
      InputStream input, BooleanSupplier cancelled, Consumer<String> eventObserver) {
    Objects.requireNonNull(input, "input");
    BooleanSupplier cancellation =
        cancelled == null ? () -> false : cancelled;
    return readBounded(input, cancellation, eventObserver);
  }

  private List<String> readBounded(
      InputStream input, BooleanSupplier cancelled, Consumer<String> eventObserver) {
    long started = System.nanoTime();
    SseAccumulator accumulator = new SseAccumulator(eventObserver);
    long totalBytes = 0L;
    try (ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor()) {
      byte[] buffer = new byte[BUFFER_SIZE];
      boolean first = true;
      while (true) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
          closeQuietly(input);
          throw ProviderException.cancelled();
        }
        Future<Integer> pending = reader.submit(() -> input.read(buffer));
        int count;
        try {
          Duration timeout = first ? firstChunkTimeout : idleTimeout;
          count = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
          pending.cancel(true);
          closeQuietly(input);
          throw ProviderException.timeout(
              first ? "first chunk" : "stream idle", exception);
        } catch (ExecutionException exception) {
          Throwable cause = exception.getCause();
          if (cause instanceof IOException ioException) {
            throw new SseDisconnectException(accumulator.finish(false), ioException);
          }
          throw ProviderException.protocol(
              "provider stream reader failed", cause);
        } catch (InterruptedException exception) {
          pending.cancel(true);
          Thread.currentThread().interrupt();
          closeQuietly(input);
          throw ProviderException.cancelled();
        }
        if (count < 0) {
          break;
        }
        if (count == 0) {
          continue;
        }
        if (first) {
          firstChunkLatencyMs =
              (System.nanoTime() - started) / 1_000_000.0d;
        }
        first = false;
        if (totalBytes > maximumBytes - count) {
          closeQuietly(input);
          throw ProviderException.tooLarge(maximumBytes);
        }
        totalBytes += count;
        accumulator.accept(buffer, count);
      }
      return accumulator.finish(true);
    }
  }

  public double firstChunkLatencyMs() {
    return firstChunkLatencyMs;
  }

  static List<String> parseText(String text) {
    Objects.requireNonNull(text, "text");
    List<String> events = new ArrayList<>();
    List<String> data = new ArrayList<>();
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    if (normalized.startsWith("\ufeff")) {
      normalized = normalized.substring(1);
    }
    String[] lines = normalized.split("\n", -1);
    for (String rawLine : lines) {
      String line = rawLine;
      if (line.isEmpty()) {
        flush(data, events);
        continue;
      }
      if (line.startsWith(":")) {
        continue;
      }
      if (!line.startsWith("data:")) {
        continue;
      }
      String value = line.substring(5);
      if (value.startsWith(" ")) {
        value = value.substring(1);
      }
      data.add(value);
    }
    flush(data, events);
    return List.copyOf(events);
  }

  private static void flush(List<String> data, List<String> events) {
    if (!data.isEmpty()) {
      events.add(String.join("\n", data));
      data.clear();
    }
  }

  private static final class SseAccumulator {
    private final Consumer<String> observer;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final List<String> data = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private boolean priorCarriageReturn;
    private boolean finished;

    private SseAccumulator(Consumer<String> observer) {
      this.observer = observer;
    }

    private void accept(byte[] bytes, int count) {
      for (int index = 0; index < count; index++) {
        int value = Byte.toUnsignedInt(bytes[index]);
        if (value == '\r') {
          finishLine(true);
          priorCarriageReturn = true;
        } else if (value == '\n') {
          if (!priorCarriageReturn) {
            finishLine(true);
          }
          priorCarriageReturn = false;
        } else {
          priorCarriageReturn = false;
          line.write(value);
        }
      }
    }

    private List<String> finish(boolean notifyObserver) {
      if (!finished) {
        if (line.size() > 0) {
          finishLine(notifyObserver);
        }
        flushEvent(notifyObserver);
        finished = true;
      }
      return List.copyOf(events);
    }

    private void finishLine(boolean notifyObserver) {
      String value = line.toString(StandardCharsets.UTF_8);
      line.reset();
      if (events.isEmpty() && data.isEmpty() && value.startsWith("\ufeff")) {
        value = value.substring(1);
      }
      if (value.isEmpty()) {
        flushEvent(notifyObserver);
        return;
      }
      if (value.startsWith(":")) {
        return;
      }
      if (!value.startsWith("data:")) {
        return;
      }
      String payload = value.substring(5);
      if (payload.startsWith(" ")) {
        payload = payload.substring(1);
      }
      data.add(payload);
    }

    private void flushEvent(boolean notifyObserver) {
      if (data.isEmpty()) {
        return;
      }
      String event = String.join("\n", data);
      data.clear();
      events.add(event);
      if (notifyObserver && observer != null) {
        observer.accept(event);
      }
    }
  }

  private static void closeQuietly(InputStream input) {
    try {
      input.close();
    } catch (IOException ignored) {
      // The transport failure already carries the useful status.
    }
  }
}
