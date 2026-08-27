package io.github.aililuola.mathproofmesh.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "The provider-free health endpoint is intentionally unauthenticated and returns static data.")
public final class HealthController {
  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.ofEntries(
        Map.entry("ok", true),
        Map.entry("version", "0.8.0"),
        Map.entry("system", "MathProofMesh"),
        Map.entry("enabled_agents", 1),
        Map.entry("activity_stream", "/solve/stream"),
        Map.entry("resume_endpoint", "/resume"),
        Map.entry("resume_stream", "/resume/stream"),
        Map.entry("checkpoint_resume_enabled", true),
        Map.entry("topology_mode", "sparse"),
        Map.entry("proof_graph_mode", "authoritative"),
        Map.entry("inspiration_mode", "bounded"));
  }
}
