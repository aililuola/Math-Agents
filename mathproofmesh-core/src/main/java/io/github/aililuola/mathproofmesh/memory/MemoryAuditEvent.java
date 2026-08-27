package io.github.aililuola.mathproofmesh.memory;

import java.util.Map;

public record MemoryAuditEvent(
    long sequence,
    String eventType,
    String itemId,
    long version,
    Map<String, String> details) {

  public MemoryAuditEvent {
    if (sequence < 1 || version < 0) {
      throw new IllegalArgumentException("memory audit sequence and version are invalid");
    }
    eventType = require(eventType, "eventType");
    itemId = require(itemId, "itemId");
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
