package io.github.aililuola.mathproofmesh.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ApiEvent;
import java.util.List;
import java.util.Objects;

final class SseEncoder {
  private final ObjectWriter writer;

  SseEncoder(ObjectMapper mapper) {
    this.writer = Objects.requireNonNull(mapper, "mapper").writerFor(ApiEvent.class);
  }

  String encode(List<ApiEvent> events) {
    int initialCapacity =
        (int) Math.min(8L * 1024 * 1024, Math.max(16L, (long) events.size() * 256L));
    StringBuilder result = new StringBuilder(initialCapacity);
    for (ApiEvent event : events) {
      result
          .append("id: ")
          .append(event.eventId())
          .append('\n')
          .append("event: ")
          .append(event.type())
          .append('\n')
          .append("data: ")
          .append(json(event))
          .append("\n\n");
    }
    if (events.isEmpty()) {
      result.append("event: heartbeat\ndata: {\"status\":\"connected\"}\n\n");
    }
    return result.toString();
  }

  private String json(ApiEvent event) {
    try {
      return writer.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("SSE event could not be serialized", exception);
    }
  }

  static long lastEventId(String value) {
    if (value == null || value.isBlank()) {
      return 0L;
    }
    try {
      long parsed = Long.parseLong(value.trim());
      return Math.max(0L, parsed);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Last-Event-ID must be a nonnegative integer");
    }
  }
}
