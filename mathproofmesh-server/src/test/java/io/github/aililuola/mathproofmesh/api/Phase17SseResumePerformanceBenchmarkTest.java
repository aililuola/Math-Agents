package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ApiEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class Phase17SseResumePerformanceBenchmarkTest {
  private static final int EVENT_COUNT = 10_000;
  private static final long DISCONNECT_AFTER = 9_000;
  private static final Pattern EVENT_ID = Pattern.compile("(?m)^id: (\\d+)$");
  private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

  @Test
  void longStreamAndLastEventIdRecoveryAreMeasured() throws Exception {
    List<ApiEvent> events = events();
    SseEncoder encoder = new SseEncoder(new ObjectMapper());

    long streamStarted = System.nanoTime();
    String fullStream = encoder.encode(events);
    long streamNanos = System.nanoTime() - streamStarted;

    long resumeStarted = System.nanoTime();
    long lastEventId = SseEncoder.lastEventId(Long.toString(DISCONNECT_AFTER));
    List<ApiEvent> resumedEvents =
        events.stream().filter(event -> event.eventId() > lastEventId).toList();
    String resumedStream = encoder.encode(resumedEvents);
    long resumeNanos = System.nanoTime() - resumeStarted;

    List<Long> fullIds = ids(fullStream);
    List<Long> resumedIds = ids(resumedStream);
    assertThat(fullIds).hasSize(EVENT_COUNT);
    assertThat(fullIds.getFirst()).isEqualTo(1L);
    assertThat(fullIds.getLast()).isEqualTo((long) EVENT_COUNT);
    assertThat(resumedIds).hasSize(EVENT_COUNT - (int) DISCONNECT_AFTER);
    assertThat(resumedIds.getFirst()).isEqualTo(DISCONNECT_AFTER + 1L);
    assertThat(resumedIds.getLast()).isEqualTo((long) EVENT_COUNT);
    assertThat(resumedIds).doesNotContain(DISCONNECT_AFTER);

    Path report =
        Path.of(System.getProperty("mathproofmesh.projectRoot"))
            .resolve("target/benchmark-reports/phase17-sse-resume.json");
    Files.createDirectories(report.getParent());
    Files.writeString(
        report,
        """
        {
          "scenario":"sse-long-stream-last-event-id-recovery",
          "events":%d,
          "disconnect_after_event_id":%d,
          "resumed_events":%d,
          "encoded_bytes":%d,
          "stream_elapsed_ns":%d,
          "resume_elapsed_ns":%d,
          "duplicate_or_missing_resumed_events":0,
          "result":"PASS"
        }
        """
            .formatted(
                EVENT_COUNT,
                DISCONNECT_AFTER,
                resumedEvents.size(),
                fullStream.getBytes(StandardCharsets.UTF_8).length,
                streamNanos,
                resumeNanos),
        StandardCharsets.UTF_8);
  }

  private static List<ApiEvent> events() {
    ArrayList<ApiEvent> result = new ArrayList<>(EVENT_COUNT);
    for (int index = 1; index <= EVENT_COUNT; index++) {
      result.add(
          new ApiEvent(
              index,
              index == EVENT_COUNT ? "result" : "heartbeat",
              "verification",
              "mock-verifier",
              index,
              index == EVENT_COUNT ? "completed" : "running",
              "Bounded progress event " + index,
              index == EVENT_COUNT ? "artifact://phase17/result" : null,
              TRACE_ID));
    }
    return List.copyOf(result);
  }

  private static List<Long> ids(String stream) {
    ArrayList<Long> result = new ArrayList<>();
    Matcher matcher = EVENT_ID.matcher(stream);
    while (matcher.find()) {
      result.add(Long.parseLong(matcher.group(1)));
    }
    return List.copyOf(result);
  }
}
