package io.github.aililuola.mathproofmesh.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SuppressWarnings({"deprecation", "removal"})
class ApiGateTest {
  private static final String TOKEN = "phase14-token";
  private static final String AUTHORIZATION = "Bearer " + TOKEN;
  private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
  private static final String SECRET = "sk-secret-must-not-leak-123456";
  private static final Pattern SSE_ID = Pattern.compile("(?m)^id: (\\d+)$");

  @TempDir Path temporaryDirectory;

  private SimpleMeterRegistry registry;
  private ApiObservability observability;
  private RunApiService service;
  private ObjectMapper mapper;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    observability = new ApiObservability(registry);
    service = new RunApiService(observability, temporaryDirectory.resolve("runs").toString(), 2);
    mapper =
        JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new HealthController(),
                new SolveController(service, mapper),
                new ResumeController(service, mapper),
                new RunQueryController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(
                new ByteArrayHttpMessageConverter(),
                new StringHttpMessageConverter(StandardCharsets.UTF_8),
                new MappingJackson2HttpMessageConverter(mapper))
            .addFilters(
                new TraceCorrelationFilter(),
                new RequestSafetyFilter(1024, 2),
                new BearerTokenFilter(TOKEN))
            .build();
  }

  @Test
  void healthIsProviderFreeWhileEveryOtherEndpointRequiresBearerAuth() throws Exception {
    mockMvc
        .perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(jsonPath("$.resume_endpoint").value("/resume"))
        .andExpect(jsonPath("$.resume_stream").value("/resume/stream"));

    mockMvc
        .perform(
            post("/solve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"problem\":\"prove 1=1\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().string("{\"detail\":\"invalid bearer token\"}"));
    mockMvc
        .perform(
            post("/solve")
                .header("Authorization", "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"problem\":\"prove 1=1\"}"))
        .andExpect(status().isUnauthorized());
    assertEquals(0.0d, registry.get("mathproofmesh.api.calls").counter().count());
  }

  @Test
  void solveResumeAndSseUseOneRedactedMonotonicContract() throws Exception {
    String body =
        "{\"problem\":\"do not echo "
            + SECRET
            + "\",\"run_id\":\"sse-run\",\"canonical_statement\":\"1=1\"}";
    MvcResult solved =
        mockMvc
            .perform(
                post("/solve/stream")
                    .header("Authorization", AUTHORIZATION)
                    .header("X-Trace-Id", TRACE_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(header().string("X-Trace-Id", TRACE_ID))
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();
    String stream = solved.getResponse().getContentAsString(StandardCharsets.UTF_8);
    List<Long> ids = eventIds(stream);
    assertFalse(ids.isEmpty());
    assertEquals(ids.stream().sorted().distinct().toList(), ids);
    assertFalse(stream.contains(SECRET));
    assertFalse(stream.contains("do not echo"));
    assertTrue(stream.contains("\"event_id\""));
    assertTrue(stream.contains("\"result_reference\""));

    String replay =
        mockMvc
            .perform(
                post("/solve/stream")
                    .header("Authorization", AUTHORIZATION)
                    .header("Last-Event-ID", "5")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertTrue(eventIds(replay).stream().allMatch(value -> value > 5));

    mockMvc
        .perform(
            post("/resume")
                .header("Authorization", AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"run_id\":\"sse-run\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.run_id").value("sse-run"))
        .andExpect(jsonPath("$.status").value("unverified"));
  }

  @Test
  void statusRoutesGraphActivityAndArtifactAreReadOnlyAndBounded() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/solve")
                    .header("Authorization", AUTHORIZATION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"problem\":\"prove by induction\",\"run_id\":\"query-run\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.run_id").value("query-run"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String reference = mapper.readTree(response).path("result_reference").asText();
    String hash = reference.substring(reference.lastIndexOf('/') + 1);

    mockMvc
        .perform(get("/runs/query-run").header("Authorization", AUTHORIZATION))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.latest_event_id").value(10));
    mockMvc
        .perform(get("/runs/query-run/activity").header("Authorization", AUTHORIZATION))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].event_id").value(1));
    mockMvc
        .perform(get("/runs/query-run/routes").header("Authorization", AUTHORIZATION))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].route_id").value("route-1"));
    mockMvc
        .perform(get("/runs/query-run/proof-graph").header("Authorization", AUTHORIZATION))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.run_id").value("query-run"));
    mockMvc
        .perform(
            get("/runs/query-run/artifacts/" + hash).header("Authorization", AUTHORIZATION))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_MARKDOWN))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Run: `query-run`")));

    assertThrows(IllegalArgumentException.class, () -> service.status("../outside"));
    assertThrows(IllegalArgumentException.class, () -> service.artifact("query-run", "../outside"));
  }

  @Test
  void malformedUnknownAndOversizedInputsFailClosed() throws Exception {
    mockMvc
        .perform(
            post("/solve")
                .header("Authorization", AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"problem\":\"x\",\"provider_url\":\"https://attacker.invalid\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("request payload is invalid"));
    mockMvc
        .perform(
            post("/resume")
                .header("Authorization", AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"run_id\":\"missing\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("run cannot be resumed or queried"));
    mockMvc
        .perform(
            post("/solve")
                .header("Authorization", AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[2048]))
        .andExpect(status().is(413));
  }

  @Test
  void concurrentRequestLimitReturns429WithoutExecutingSecondChain() throws Exception {
    RequestSafetyFilter filter = new RequestSafetyFilter(1024, 1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor.submit(
          () -> {
            try {
              filter.doFilter(
                  new MockHttpServletRequest(),
                  new MockHttpServletResponse(),
                  (request, response) -> {
                    entered.countDown();
                    try {
                      assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                      Thread.currentThread().interrupt();
                      throw new jakarta.servlet.ServletException(exception);
                    }
                  });
            } catch (Throwable exception) {
              failure.set(exception);
            }
          });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      MockHttpServletResponse rejected = new MockHttpServletResponse();
      filter.doFilter(new MockHttpServletRequest(), rejected, (request, response) -> {});
      assertEquals(429, rejected.getStatus());
      release.countDown();
    }
    assertEquals(null, failure.get());
  }

  @Test
  void cliCommandsShareTheMockApplicationServiceAndServeOnlyLoopback() throws Exception {
    Path config = temporaryDirectory.resolve("config.yaml");
    Path problem = temporaryDirectory.resolve("problem.txt");
    Files.writeString(config, "provider: mock\n", StandardCharsets.UTF_8);
    Files.writeString(problem, "Prove 1 = 1.\n", StandardCharsets.UTF_8);
    StringWriter output = new StringWriter();
    StringWriter error = new StringWriter();
    AtomicReference<String> launched = new AtomicReference<>();

    assertEquals(
        0,
        executeCli(
            output,
            error,
            (path, host, port) -> launched.set(host + ":" + port),
            "solve",
            problem.toString(),
            "--config",
            config.toString(),
            "--run-id",
            "cli-run"));
    assertTrue(output.toString().contains("\"run_id\":\"cli-run\""));
    clear(output);
    assertEquals(
        0,
        executeCli(
            output,
            error,
            (path, host, port) -> {},
            "resume",
            "cli-run",
            "--config",
            config.toString()));
    assertTrue(output.toString().contains("\"status\":\"unverified\""));
    clear(output);
    assertEquals(0, executeCli(output, error, (path, host, port) -> {}, "demo"));
    assertTrue(output.toString().contains("\"run_id\":\"demo-run\""));
    clear(output);
    assertEquals(
        0,
        executeCli(
            output,
            error,
            (path, host, port) -> {},
            "probe",
            "--config",
            config.toString(),
            "--completion"));
    assertTrue(output.toString().contains("\"completion_checked\":true"));
    clear(output);
    assertEquals(
        0,
        executeCli(
            output,
            error,
            (path, host, port) -> launched.set(host + ":" + port),
            "serve",
            "--config",
            config.toString(),
            "--host",
            "127.0.0.1",
            "--port",
            "0"));
    assertEquals("127.0.0.1:0", launched.get());
    assertEquals("", error.toString());
  }

  @Test
  void metricsAndTraceIdsCorrelateWithoutPromptOrSecretFields() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/solve")
                    .header("Authorization", AUTHORIZATION)
                    .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"problem\":\""
                            + SECRET
                            + "\",\"run_id\":\"trace-run\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Trace-Id", TRACE_ID))
            .andExpect(jsonPath("$.trace_id").value(TRACE_ID))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertFalse(response.contains(SECRET));
    assertFalse(response.contains("problem"));
    assertFalse(response.contains("prompt"));
    assertEquals(TRACE_ID, observability.lastTrace("solve"));
    assertEquals(1.0d, registry.get("mathproofmesh.api.calls").counter().count());
    assertEquals(1L, registry.get("mathproofmesh.api.latency").timer().count());
    assertEquals(1.0d, registry.get("mathproofmesh.checkpoints").counter().count());
    assertNotNull(registry.find("mathproofmesh.queue.depth").gauge());
  }

  private int executeCli(
      StringWriter output,
      StringWriter error,
      MathProofMeshCommand.ServerLauncher launcher,
      String... args) {
    return MathProofMeshCommand.commandLine(
            service, new PrintWriter(output, true), new PrintWriter(error, true), launcher)
        .execute(args);
  }

  private static List<Long> eventIds(String stream) {
    List<Long> result = new ArrayList<>();
    Matcher matcher = SSE_ID.matcher(stream);
    while (matcher.find()) {
      result.add(Long.parseLong(matcher.group(1)));
    }
    return List.copyOf(result);
  }

  private static void clear(StringWriter writer) {
    writer.getBuffer().setLength(0);
  }
}
