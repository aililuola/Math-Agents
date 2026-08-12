package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.MathProofMeshApplication;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Embedded Spring Boot lifecycle bound to a random loopback port. */
public final class LocalDesktopServer implements AutoCloseable {
  private final DesktopPaths paths;
  private final String sessionToken;
  private ConfigurableApplicationContext context;
  private int port;

  public LocalDesktopServer(DesktopPaths paths, String sessionToken) {
    this.paths = Objects.requireNonNull(paths, "paths");
    this.sessionToken = Objects.requireNonNull(sessionToken, "sessionToken");
  }

  public synchronized int start() {
    if (context != null) {
      throw new IllegalStateException("desktop server is already running");
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("server.address", "127.0.0.1");
    properties.put("server.port", "0");
    properties.put("management.server.address", "127.0.0.1");
    properties.put("management.server.port", "0");
    properties.put("mathproofmesh.api.token", sessionToken);
    properties.put("mathproofmesh.api.run-root", paths.runs().toString());
    properties.put("mathproofmesh.desktop.enabled", "true");
    properties.put("mathproofmesh.desktop.root", paths.root().toString());
    properties.put("mathproofmesh.desktop.session-token", sessionToken);
    properties.put("spring.main.banner-mode", "off");
    properties.put("logging.level.root", "WARN");
    ConfigurableApplicationContext started =
        new SpringApplicationBuilder(MathProofMeshApplication.class)
            .web(WebApplicationType.SERVLET)
            .initializers(
                applicationContext ->
                    applicationContext
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource("desktopOverrides", properties)))
            .logStartupInfo(false)
            .run(
                "--debug=false",
                "--logging.level.root=WARN",
                "--spring.main.banner-mode=off");
    if (!(started instanceof WebServerApplicationContext webContext)) {
      started.close();
      throw new IllegalStateException("desktop Spring context has no web server");
    }
    WebServer webServer =
        Objects.requireNonNull(webContext.getWebServer(), "desktop web server");
    int selected = webServer.getPort();
    if (selected < 1 || selected > 65535) {
      started.close();
      throw new IllegalStateException("desktop server did not bind a valid port");
    }
    context = started;
    port = selected;
    return selected;
  }

  public synchronized int port() {
    if (context == null) {
      throw new IllegalStateException("desktop server is not running");
    }
    return port;
  }

  @Override
  public synchronized void close() {
    if (context != null) {
      context.close();
      context = null;
      port = 0;
    }
  }
}
