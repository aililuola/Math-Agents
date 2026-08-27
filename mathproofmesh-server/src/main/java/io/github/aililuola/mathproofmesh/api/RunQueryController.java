package io.github.aililuola.mathproofmesh.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ArtifactPayload;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ProofGraphView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "Read-only run endpoints are protected by bearer auth and validate every path token.")
public final class RunQueryController {
  private final RunApiService service;

  public RunQueryController(RunApiService service) {
    this.service = service;
  }

  @GetMapping("/runs/{runId}")
  public RunView run(@PathVariable String runId) {
    return service.status(runId);
  }

  @GetMapping("/runs/{runId}/activity")
  public List<RunApiModels.ApiEvent> activity(@PathVariable String runId) {
    return service.eventsAfter(runId, 0L);
  }

  @GetMapping("/runs/{runId}/routes")
  public List<RouteView> routes(@PathVariable String runId) {
    return service.routes(runId);
  }

  @GetMapping("/runs/{runId}/proof-graph")
  public ProofGraphView proofGraph(@PathVariable String runId) {
    return service.proofGraph(runId);
  }

  @GetMapping("/runs/{runId}/artifacts/{hash}")
  public ResponseEntity<byte[]> artifact(@PathVariable String runId, @PathVariable String hash) {
    ArtifactPayload artifact = service.artifact(runId, hash);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(artifact.mediaType()));
    headers.setContentLength(artifact.bytes().length);
    headers.setContentDisposition(
        ContentDisposition.attachment().filename("artifact-" + artifact.hash() + ".md").build());
    headers.set("X-Content-Type-Options", "nosniff");
    return ResponseEntity.ok().headers(headers).body(artifact.bytes());
  }
}
