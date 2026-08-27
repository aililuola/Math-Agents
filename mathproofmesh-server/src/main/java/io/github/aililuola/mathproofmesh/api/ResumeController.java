package io.github.aililuola.mathproofmesh.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "All resume endpoints are bounded and protected by the ordered bearer filter.")
public final class ResumeController {
  private final RunApiService service;
  private final SseEncoder sse;

  public ResumeController(RunApiService service, ObjectMapper mapper) {
    this.service = service;
    this.sse = new SseEncoder(mapper);
  }

  @PostMapping(path = "/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
  public RunView resume(@RequestBody ResumeRequest request) {
    return service.resume(request);
  }

  @PostMapping(
      path = "/resume/stream",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<String> resumeStream(
      @RequestBody ResumeRequest request,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    RunView run = service.resume(request);
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .cacheControl(CacheControl.noCache())
        .header("X-Accel-Buffering", "no")
        .header("X-Trace-Id", run.traceId())
        .body(sse.encode(service.eventsAfter(run.runId(), SseEncoder.lastEventId(lastEventId))));
  }
}
