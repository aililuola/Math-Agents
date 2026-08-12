package io.github.aililuola.mathproofmesh.contract;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public final class PythonIsoTimestampCodec {
  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  private PythonIsoTimestampCodec() {}

  public static String now() {
    return now(Clock.systemUTC());
  }

  static String now(Clock clock) {
    OffsetDateTime timestamp =
        OffsetDateTime.now(clock)
            .withOffsetSameInstant(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS);
    String base = timestamp.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"));
    int micros = timestamp.getNano() / 1_000;
    return micros == 0
        ? base + "+00:00"
        : base + ".%06d+00:00".formatted(micros);
  }

  public static OffsetDateTime parse(String value) {
    try {
      return OffsetDateTime.parse(ContractStrings.required("timestamp", value), FORMATTER);
    } catch (RuntimeException exception) {
      throw new ContractValidationException("invalid ISO-8601 timestamp: " + value, exception);
    }
  }
}
