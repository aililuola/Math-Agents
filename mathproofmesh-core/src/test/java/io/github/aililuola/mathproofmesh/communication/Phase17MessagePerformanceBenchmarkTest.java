package io.github.aililuola.mathproofmesh.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17MessagePerformanceBenchmarkTest {
  private static final int SMALL_CASES = 2_000;
  private static final int LARGE_CASES = 10_000;

  @Test
  void tenThousandAdmissionsDeduplicationsAndDeliveriesScaleBelowQuadratic()
      throws IOException {
    runCases(500);
    Sample small = runCases(SMALL_CASES);
    Sample large = runCases(LARGE_CASES);
    double growth = (double) large.elapsedNanos / Math.max(1L, small.elapsedNanos);

    assertEquals(LARGE_CASES, large.decisions);
    assertEquals(9_001, large.persistedMessages);
    assertEquals(1, large.deliveries);
    assertTrue(
        growth < 15.0,
        () -> "10k/2k growth " + growth + " approaches quadratic scaling");

    Path report =
        Path.of(System.getProperty("mathproofmesh.projectRoot"))
            .resolve("target/benchmark-reports/phase17-message.json");
    Files.createDirectories(report.getParent());
    Files.writeString(
        report,
        """
        {
          "scenario":"10000-message-admission-dedup-delivery",
          "small_cases":%d,
          "large_cases":%d,
          "small_elapsed_ns":%d,
          "large_elapsed_ns":%d,
          "growth_ratio":%.6f,
          "persisted_messages":%d,
          "deliveries":%d,
          "result":"PASS"
        }
        """
            .formatted(
                SMALL_CASES,
                LARGE_CASES,
                small.elapsedNanos,
                large.elapsedNanos,
                growth,
                large.persistedMessages,
                large.deliveries),
        StandardCharsets.UTF_8);
  }

  private static Sample runCases(int count) {
    RouteRegistry routes = CommunicationFixtures.routes();
    InMemoryMessageRepository repository = new InMemoryMessageRepository();
    MessageBrokerPolicy policy =
        new MessageBrokerPolicy(
            "1", 32_000, 64, 64, 1, 1, 3, 0, 0.9,
            true, true, true, true, true, true);
    MessageBroker broker =
        CommunicationFixtures.broker(
            policy,
            routes,
            CommunicationFixtures.acceptingDependencies(),
            repository);

    int uniqueCases = count * 9 / 10;
    long started = System.nanoTime();
    for (int index = 0; index < count; index++) {
      var message =
          index < uniqueCases
              ? CommunicationFixtures.insight("unique-" + index, List.of("route-b"))
              : CommunicationFixtures.fact("duplicate-" + index, List.of("route-b"));
      assertTrue(broker.publish(message, "referee-a", 1).accepted());
    }
    long elapsed = System.nanoTime() - started;
    MessageStoreSnapshot snapshot = repository.snapshot();
    return new Sample(
        elapsed,
        broker.decisions().size(),
        snapshot.messages().size(),
        snapshot.deliveries().size());
  }

  private record Sample(
      long elapsedNanos, int decisions, int persistedMessages, int deliveries) {}
}
