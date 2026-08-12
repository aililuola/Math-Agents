package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicitly enabled live regression harness for an existing desktop checkpoint. */
@EnabledIfEnvironmentVariable(named = "MATHPROOFMESH_LIVE_E2E", matches = "true")
final class DesktopLiveEndToEndTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> TERMINAL =
      Set.of("completed", "failed", "cancelled", "interrupted");

  @Test
  @Timeout(value = 4, unit = TimeUnit.HOURS)
  void runsARealSolveUntilItReachesAnOperationallySuccessfulTerminalState() throws Exception {
    String sourceRunId = requiredEnvironment("MATHPROOFMESH_LIVE_RUN_ID");
    DesktopPaths paths = DesktopPaths.discover();
    Path sourceRunDirectory = paths.safeRunDirectory(sourceRunId);
    assertThat(sourceRunDirectory).isDirectory();

    String token = UUID.randomUUID().toString() + UUID.randomUUID();
    try (LocalDesktopServer server = new LocalDesktopServer(paths, token)) {
      int port = server.start();
      String cookie = bootstrap(port, token);
      String activeRunId =
          Boolean.parseBoolean(System.getenv("MATHPROOFMESH_LIVE_START_NEW"))
              ? start(port, cookie, Files.readString(sourceRunDirectory.resolve("problem.txt")))
              : resume(port, cookie, sourceRunId);
      System.out.println("LIVE RUN " + activeRunId);
      awaitTerminal(paths.safeRunDirectory(activeRunId));
    }
  }

  private static String bootstrap(int port, String token) throws Exception {
    URI uri =
        URI.create(
            "http://127.0.0.1:"
                + port
                + "/?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8));
    HttpResponse<String> response =
        client()
            .send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(303);
    return response
        .headers()
        .firstValue("set-cookie")
        .orElseThrow(() -> new IllegalStateException("desktop session cookie is missing"))
        .split(";", 2)[0];
  }

  private static String resume(int port, String cookie, String runId) throws Exception {
    String body =
        "{\"profile\":\"proof_control_active\",\"resume_mode\":\"normal\"}";
    HttpResponse<String> response =
        client()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/runs/" + runId + "/resume"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Cookie", cookie)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    return responseRunId(response.body());
  }

  private static String start(int port, String cookie, String problem) throws Exception {
    String body =
        JSON.writeValueAsString(
            Map.of("problem", problem, "profile", "proof_control_active"));
    HttpResponse<String> response =
        client()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/runs"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Cookie", cookie)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    return responseRunId(response.body());
  }

  private static String responseRunId(String responseBody) throws IOException {
    String runId = JSON.readTree(responseBody).path("run").path("run_id").asText();
    if (runId.isBlank()) {
      throw new IllegalStateException("desktop response did not contain a run_id");
    }
    return runId;
  }

  private static void awaitTerminal(Path runDirectory) throws Exception {
    Path metadataFile = runDirectory.resolve("desktop_run.json");
    Path activityFile = runDirectory.resolve("activity.jsonl");
    int displayedActivities = existingLineCount(activityFile);
    Instant deadline = Instant.now().plus(Duration.ofHours(3));
    while (Instant.now().isBefore(deadline)) {
      displayedActivities = displayNewActivities(activityFile, displayedActivities);
      JsonNode metadata = readJsonRetrying(metadataFile);
      String lifecycle = metadata.path("lifecycle").asText();
      if (TERMINAL.contains(lifecycle)) {
        displayNewActivities(activityFile, displayedActivities);
        assertThat(lifecycle)
            .withFailMessage(
                "live run terminated as %s: %s",
                lifecycle, metadata.path("error").asText("no error detail"))
            .isEqualTo("completed");
        assertThat(runDirectory.resolve("structured").resolve("run_result.json"))
            .isRegularFile();
        return;
      }
      Thread.sleep(Duration.ofSeconds(5));
    }
    throw new AssertionError("live run did not reach a terminal state within three hours");
  }

  private static int existingLineCount(Path activityFile) throws IOException {
    return Files.isRegularFile(activityFile) ? Files.readAllLines(activityFile).size() : 0;
  }

  private static int displayNewActivities(Path activityFile, int displayed) throws IOException {
    if (!Files.isRegularFile(activityFile)) {
      return displayed;
    }
    List<String> lines = Files.readAllLines(activityFile, StandardCharsets.UTF_8);
    for (int index = displayed; index < lines.size(); index++) {
      JsonNode activity = JSON.readTree(lines.get(index));
      System.out.printf(
          "LIVE %s %-10s %-24s %s%n",
          activity.path("sequence").asText(),
          activity.path("status").asText(),
          activity.path("stage").asText(),
          activity.path("title").asText());
    }
    return lines.size();
  }

  private static JsonNode readJsonRetrying(Path path) throws Exception {
    Exception last = null;
    for (int attempt = 0; attempt < 5; attempt++) {
      try {
        return JSON.readTree(path.toFile());
      } catch (IOException | RuntimeException exception) {
        last = exception;
        Thread.sleep(100L);
      }
    }
    throw last == null ? new IllegalStateException("metadata is unavailable") : last;
  }

  private static HttpClient client() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required for the live regression harness");
    }
    return value.strip();
  }
}
