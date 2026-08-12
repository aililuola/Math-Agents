package io.github.aililuola.mathproofmesh.api;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** ANSI-free console projection; compact mode prints one terminal snapshot per task. */
public final class ConsoleActivityView implements Consumer<ActivityEvent>, AutoCloseable {
  public enum Mode {
    COMPACT,
    DETAILED
  }

  private final PrintWriter output;
  private final Mode mode;
  private final Map<String, ActivityEvent> latest = new LinkedHashMap<>();
  private boolean closed;

  public ConsoleActivityView(PrintWriter output, Mode mode) {
    this.output = Objects.requireNonNull(output, "output");
    this.mode = mode == null ? Mode.COMPACT : mode;
  }

  @Override
  public synchronized void accept(ActivityEvent event) {
    if (closed) {
      return;
    }
    latest.put(event.taskId(), event);
    if (mode == Mode.DETAILED || terminal(event.status())) {
      print(event);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    if (mode == Mode.COMPACT) {
      for (ActivityEvent event : latest.values()) {
        if (!terminal(event.status())) {
          print(event);
        }
      }
    }
    output.flush();
    closed = true;
  }

  private void print(ActivityEvent event) {
    output.print('[');
    output.print(ActivityStream.formatElapsed(event.elapsedMs() / 1000));
    output.print("] ");
    output.print(event.status().wireName());
    output.print(' ');
    output.print(event.title());
    if (!event.detail().isBlank()) {
      output.print(": ");
      output.print(event.detail());
    }
    output.println();
  }

  private static boolean terminal(ActivityStatus status) {
    return status == ActivityStatus.COMPLETED
        || status == ActivityStatus.WARNING
        || status == ActivityStatus.FAILED;
  }
}
