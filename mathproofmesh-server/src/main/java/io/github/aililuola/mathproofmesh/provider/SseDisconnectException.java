package io.github.aililuola.mathproofmesh.provider;

import java.util.List;

@SuppressWarnings("serial")
final class SseDisconnectException extends RuntimeException {
  private final List<String> events;

  SseDisconnectException(List<String> events, Throwable cause) {
    super("provider SSE transport disconnected", cause);
    this.events = List.copyOf(events);
  }

  List<String> events() {
    return events;
  }
}
