package io.github.aililuola.mathproofmesh.compatibility.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProofControlBenchmarkTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void benchmarkFixtureIsExactOfflineAuthority(
      String caseId, String expectedSha256) throws IOException, NoSuchAlgorithmException {
    String resource = "/benchmarks/proof_control/" + caseId + ".json";
    byte[] bytes;
    try (InputStream stream = getClass().getResourceAsStream(resource)) {
      assertNotNull(stream, resource);
      bytes = stream.readAllBytes();
    }
    assertEquals(
        expectedSha256,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    JsonNode fixture = JSON.readTree(bytes);
    assertEquals(caseId, fixture.path("case_id").textValue());
    assertTrue(fixture.size() >= 3);
    String serialized = fixture.toString().toLowerCase(java.util.Locale.ROOT);
    assertFalse(serialized.contains("api_key"));
    assertFalse(serialized.contains("base_url"));
    assertFalse(serialized.contains("provider_call"));
  }

  static Stream<Arguments> fixtures() {
    return Map.of(
            "abstract_realizer_repair",
                "239454fb4bdcaf9a077765513a6c1b5e84f90fd222477573739874c31c28840a",
            "bounded_gap_nonperiodic",
                "ab9d18fbbf89003f829a237100328618e50cf0ca79ae2353ab14f0d81a70942b",
            "common_mode_assumption",
                "0dcbdd81d7b10ebf8a322a2e9fe3289897c13625080a2982c115396c712f180b",
            "eventual_to_global",
                "4e9335c3ad96c126c1fcd5034ce656ba20b8cee8394aa01ab88c5cea10ff249e",
            "image_inclusion_not_surjective",
                "a30365307bad12b46ae43b2776802c18e0ef8fe0cfc936fc6807fde5111950aa",
            "message_without_use",
                "aa717ca65043ed4d856f51ec9d43218406e6baad39b785ddce323e332c54cfa2",
            "obligation_compression",
                "253fcc04d331019b3548abb0a4794bc5d9115107ef5387f06c547e210dad9069",
            "occurrence_induction",
                "f5d7990b38b51e0ad069ab93996c093d770fd131a272c9fe934f5f76b64637f9",
            "overstrong_target",
                "fd01b823edde03e71fdef99fa2fcf7209c2d1c5ac42ef7d67dd0e813d5e363e7",
            "projection_information_loss",
                "4a07ee62ada388f0db6381c5041e881c49083bc73a1b1661a8fb9cbb6e6a9473")
        .entrySet()
        .stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
  }
}
