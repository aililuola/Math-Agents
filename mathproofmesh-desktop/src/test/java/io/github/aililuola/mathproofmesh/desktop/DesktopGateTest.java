package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopGateTest {
  private static final Map<String, String> RESOURCE_HASHES =
      Map.of(
          "index.html", "f6c6233e534b26c27c9b67d22248e59d3aa8a2149abcf34fe85dd4bb44e4d5aa",
          "app.js", "a8e8d14e42904d7b866b707e8bea64097e857abd83d95ea431cd4768f95e961d",
          "styles.css", "de4e0a207b5f87cf8716a338b395428818e6e2344ca58083c6040d34478897eb",
          "topology.js", "cb751c70696a6324c33e65c10dc7500b277594cc79b90f99a8dd0a9b4d2681da",
          "app-icon.png", "8150c2799bef86b103547d54459f59a090b1477a56a6f1c3b697c7d77a659f23");

  @TempDir Path temporaryDirectory;

  @Test
  void localServerLifecycleCookieCspAndHealthCheck() throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("server"));
    DesktopLauncher.healthCheck(paths);
    try (DesktopTestSupport.HttpSession session = new DesktopTestSupport.HttpSession(paths)) {
      assertTrue(session.port() > 0);
      assertNotEquals(8080, session.port());
      String setCookie = session.setCookie().toLowerCase(java.util.Locale.ROOT);
      assertTrue(setCookie.contains("httponly"));
      assertTrue(setCookie.contains("samesite=strict"));
      HttpResponse<String> index = session.get("/");
      String csp =
          index
              .headers()
              .firstValue("content-security-policy")
              .orElseThrow();
      assertTrue(csp.contains("default-src 'self'"));
      assertTrue(csp.contains("connect-src 'self'"));
      assertTrue(csp.contains("object-src 'none'"));
      assertEquals(401, session.getWithoutCookie("/api/bootstrap").statusCode());
      assertEquals(200, session.getWithoutCookie("/health").statusCode());
    }
  }

  @Test
  void windowsDpapiRoundTripCorruptionAndCurrentUserScope() {
    Assumptions.assumeTrue(WindowsDpapiProtector.isWindows());
    WindowsDpapiProtector protector = new WindowsDpapiProtector();
    byte[] plaintext = "dpapi-phase-15-secret".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = protector.protect(plaintext);
    assertFalse(Arrays.equals(plaintext, encrypted));
    assertArrayEquals(plaintext, protector.unprotect(encrypted));
    assertEquals("windows-dpapi-current-user", protector.protectionId());
    assertTrue(protector.currentUserScopeOnly());
    assertTrue(protector.entropyId().startsWith("sha256:"));
    encrypted[encrypted.length / 2] ^= 0x5a;
    assertThrows(RuntimeException.class, () -> protector.unprotect(encrypted));
  }

  @Test
  void dpapiVaultRejectsCorruptionAndNeverEmitsPlaintext() throws Exception {
    Assumptions.assumeTrue(WindowsDpapiProtector.isWindows());
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("vault"));
    String secret = "sk-phase15-plaintext-must-not-appear";
    CredentialVault vault =
        new CredentialVault(
            paths.credentialsFile(), DesktopTestSupport.MAPPER, new WindowsDpapiProtector());
    vault.set("DEEPSEEK_AGENT_1_KEY", secret, true);
    String stored = Files.readString(paths.credentialsFile());
    assertFalse(stored.contains(secret));
    assertEquals(secret, new CredentialVault(paths.credentialsFile(), DesktopTestSupport.MAPPER).get(
        "DEEPSEEK_AGENT_1_KEY"));

    JsonNode root = DesktopTestSupport.MAPPER.readTree(stored);
    ObjectNode items = (ObjectNode) root.path("items");
    String encoded = items.path("DEEPSEEK_AGENT_1_KEY").asText();
    byte[] corrupted = Base64.getDecoder().decode(encoded);
    corrupted[corrupted.length / 2] ^= 0x33;
    items.put("DEEPSEEK_AGENT_1_KEY", Base64.getEncoder().encodeToString(corrupted));
    Files.writeString(paths.credentialsFile(), DesktopTestSupport.MAPPER.writeValueAsString(root));
    assertEquals(
        null,
        new CredentialVault(paths.credentialsFile(), DesktopTestSupport.MAPPER)
            .get("DEEPSEEK_AGENT_1_KEY"));
    String safe = MainFunctions.safeError(new IllegalStateException("api_key=" + secret));
    assertFalse(safe.contains(secret));
    assertTrue(safe.contains("[REDACTED]"));
    if (Files.isRegularFile(paths.logFile())) {
      assertFalse(Files.readString(paths.logFile()).contains(secret));
    }
  }

  @Test
  void copiedWorkbenchAssetsAreExactAndExposeCriticalUi() throws Exception {
    Path web = Path.of("src", "main", "resources", "web");
    Map<String, Path> resources = new LinkedHashMap<>();
    resources.put("index.html", web.resolve("index.html"));
    resources.put("app.js", web.resolve("assets").resolve("app.js"));
    resources.put("styles.css", web.resolve("assets").resolve("styles.css"));
    resources.put("topology.js", web.resolve("assets").resolve("topology.js"));
    resources.put("app-icon.png", web.resolve("assets").resolve("app-icon.png"));
    for (Map.Entry<String, Path> resource : resources.entrySet()) {
      assertEquals(RESOURCE_HASHES.get(resource.getKey()), sha256(resource.getValue()));
    }
    String index = Files.readString(resources.get("index.html"));
    for (String id :
        java.util.List.of(
            "topology-view",
            "reasoning-dock",
            "activity-list",
            "run-list",
            "clarification-dialog")) {
      assertTrue(index.contains("id=\"" + id + "\""), id);
    }
    assertTrue(Files.readString(resources.get("topology.js")).contains("MathProofMeshTopology"));
    assertTrue(
        Files.readString(resources.get("app.js"))
            .contains("window.__mathproofmeshContextMenuAt"));
    assertTrue(Files.readString(resources.get("app.js")).contains("resolveReasoningNode"));
    assertTrue(Files.readString(resources.get("app.js")).contains("state.topology?.tick(now)"));
    assertTrue(Files.readString(resources.get("topology.js")).contains("taskDuration(event"));
  }

  @Test
  void navigationAndSingleInstanceBoundariesAreClosed() {
    String origin = "http://127.0.0.1:49152";
    assertTrue(DesktopLauncher.allowedNavigation(origin, origin + "/api/bootstrap"));
    assertFalse(DesktopLauncher.allowedNavigation(origin, "https://example.com/"));
    assertFalse(DesktopLauncher.allowedNavigation(origin, "file:///C:/secret.txt"));
    assertFalse(DesktopLauncher.allowedNavigation(origin, "http://127.0.0.1:49153/"));
    assertFalse(DesktopLauncher.allowedNavigation(origin, "http://localhost:49152/"));
    assertEquals(
        "window.__mathproofmeshContextMenuAt(12,34)",
        DesktopLauncher.contextMenuScript(12.9d, 34.8d));
    assertEquals(
        "window.__mathproofmeshContextMenuAt(0,100000)",
        DesktopLauncher.contextMenuScript(Double.NaN, 200_000d));

    Path lock = temporaryDirectory.resolve("instance.lock");
    try (SingleInstance first = new SingleInstance(lock);
        SingleInstance second = new SingleInstance(lock)) {
      assertTrue(first.acquire());
      assertFalse(second.acquire());
    }
  }

  @Test
  void packagingPipelineRequiresPortableInstallerHealthAndChecksums() throws Exception {
    Path script = Path.of("..", "scripts", "package-desktop.ps1").normalize();
    String content = Files.readString(script);
    assertTrue(content.contains("--type app-image"));
    assertTrue(content.contains("--type exe"));
    assertTrue(content.contains("--health-check"));
    assertTrue(content.contains("Compress-Archive"));
    assertTrue(content.contains("SHA256SUMS.txt"));
    assertTrue(content.contains(".tools/jdk-25"));
    assertTrue(content.contains("scripts/run-maven.ps1"));
  }

  private static String sha256(Path path) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
}
