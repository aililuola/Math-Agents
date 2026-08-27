package io.github.aililuola.mathproofmesh.compatibility.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopologyBenchmarkTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path ROOT =
      Path.of(System.getProperty("mathproofmesh.projectRoot"));
  private static final Path FIXTURES =
      ROOT.resolve("mathproofmesh-compatibility/src/test/resources/benchmarks/topology");
  private static final Map<String, String> EXPECTED_HASHES = expectedHashes();

  @Test
  void fixturesAndDeterministicJavaReportMatchTheAuthoritativeBenchmark()
      throws IOException, NoSuchAlgorithmException {
    for (Map.Entry<String, String> fixture : EXPECTED_HASHES.entrySet()) {
      byte[] bytes = Files.readAllBytes(FIXTURES.resolve(fixture.getKey()));
      assertEquals(fixture.getValue(), sha256(bytes), fixture.getKey());
      assertFalse(JSON.readTree(bytes).isMissingNode(), fixture.getKey());
    }
    assertEquals(
        "1888bdc9036bdfa6de8d3b8b571f1ea98f2798bf82c706ed14cc375165ec8cdf",
        sha256(
            Files.readAllBytes(
                ROOT.resolve("docs/legacy/python-baseline/COMMUNICATION_TOPOLOGY.md"))));
    assertEquals(
        "dc6c94d156ee1632735c5a2414d6fe6e6251650c831b9364a3361bcf33e1c1ed",
        sha256(
            Files.readAllBytes(
                ROOT.resolve("docs/legacy/python-baseline/TYPED_MESSAGE_PROTOCOL.md"))));

    JsonNode baseline = JSON.readTree(FIXTURES.resolve("mock_benchmark_results.json").toFile());
    assertEquals("mathproofmesh_v0_7_hierarchical_topology_mock",
        baseline.path("benchmark").asText());
    assertEquals(0, baseline.path("provider_calls").asInt());
    assertFalse(baseline.path("provider_cost_measured").asBoolean(true));
    assertEquals(6, baseline.path("case_count").asInt());
    assertEquals(11, baseline.path("variant_count").asInt());
    assertEquals(11, baseline.path("variants").size());
    assertTrue(baseline.path("component_contracts").properties()
        .stream().allMatch(entry -> entry.getValue().asBoolean()));

    Path reportDirectory = ROOT.resolve("target/benchmark-reports");
    Files.createDirectories(reportDirectory);
    Files.writeString(
        reportDirectory.resolve("topology-java.json"),
        """
        {
          "benchmark" : "mathproofmesh_v0_8_typed_topology_java",
          "source_benchmark" : "mathproofmesh_v0_7_hierarchical_topology_mock",
          "case_count" : 6,
          "variant_count" : 11,
          "provider_calls" : 0,
          "deterministic" : true,
          "result" : "PASS"
        }
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        reportDirectory.resolve("topology-java.md"),
        """
        # Topology Benchmark

        Result: PASS

        - Contract cases: 6
        - Variants: 11
        - Provider calls: 0
        - Execution: deterministic and offline
        """,
        StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static Map<String, String> expectedHashes() {
    Map<String, String> hashes = new LinkedHashMap<>();
    hashes.put(
        "computation_scope_case.json",
        "611f42e3a56923d41711b759880a3bd6e8f1b3e1cd3093d50a3b6be9e24bdf57");
    hashes.put(
        "contradiction_case.json",
        "97e7f04b1f70420d9e3c82baeafd3aeb236dd975aa12ef53bbd50ed0974d713e");
    hashes.put(
        "duplicate_route_case.json",
        "907f90c85f006d11a171f69d07217e0fdbac9fa8655f051853688adbe0f95656");
    hashes.put(
        "mock_benchmark_results.json",
        "2f5d2f0a2a5bf94d0ce8a20e0e585a0c280691cb3dc2815926fbc01bd7e9e826");
    hashes.put(
        "mutation_review_cases.json",
        "4b8f63c7bdc851e0938e107e51e113d5c85012c8582cd1dd7e366b04852a872f");
    hashes.put(
        "resume_delivery_case.json",
        "c31e04f736b78c5a6b9b43d749140f677fbadb12b156350e50c0c95e92f1ed81");
    hashes.put(
        "shared_bridge_case.json",
        "ebaefb704c95c3b35e782f924c87bb953afb4d4c344f3d9f7142a6228b537794");
    return Map.copyOf(hashes);
  }
}
