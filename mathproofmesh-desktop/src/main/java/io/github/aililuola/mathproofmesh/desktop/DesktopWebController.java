package io.github.aililuola.mathproofmesh.desktop;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** Classpath-only workbench resources. No original Python directory is referenced at runtime. */
@Controller
@ConditionalOnProperty(name = "mathproofmesh.desktop.enabled", havingValue = "true")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "The ordered desktop session filter protects both endpoints; assets are selected from"
            + " a fixed classpath allowlist and the bootstrap token is never rendered.")
public final class DesktopWebController {
  private static final Map<String, MediaType> TYPES =
      Map.of(
          "app.js", MediaType.valueOf("text/javascript"),
          "topology.js", MediaType.valueOf("text/javascript"),
          "styles.css", MediaType.valueOf("text/css"),
          "app-icon.png", MediaType.IMAGE_PNG);

  private final String token;

  public DesktopWebController(
      @org.springframework.beans.factory.annotation.Value(
              "${mathproofmesh.desktop.session-token}")
          String token) {
    this.token = token;
  }

  @GetMapping("/")
  ResponseEntity<byte[]> index(@RequestParam(required = false) String token) {
    if (token != null) {
      ResponseCookie cookie =
          ResponseCookie.from(DesktopSessionFilter.COOKIE, this.token)
              .httpOnly(true)
              .secure(false)
              .sameSite("Strict")
              .path("/")
              .build();
      return ResponseEntity.status(303)
          .header(HttpHeaders.LOCATION, "/")
          .header(HttpHeaders.SET_COOKIE, cookie.toString())
          .body(new byte[0]);
    }
    return resource("web/index.html", MediaType.TEXT_HTML);
  }

  @GetMapping("/assets/{name:[A-Za-z0-9._-]+}")
  ResponseEntity<byte[]> asset(@PathVariable String name) {
    MediaType type = TYPES.get(name);
    if (type == null) {
      return ResponseEntity.notFound().build();
    }
    return resource("web/assets/" + name, type);
  }

  private static ResponseEntity<byte[]> resource(String name, MediaType type) {
    try {
      byte[] bytes = new ClassPathResource(name).getInputStream().readAllBytes();
      MediaType resolved =
          type.equals(MediaType.TEXT_HTML)
              ? new MediaType("text", "html", StandardCharsets.UTF_8)
              : type;
      return ResponseEntity.ok().contentType(resolved).body(bytes);
    } catch (IOException exception) {
      return ResponseEntity.notFound().build();
    }
  }
}
