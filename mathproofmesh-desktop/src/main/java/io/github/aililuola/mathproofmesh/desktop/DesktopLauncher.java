package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JavaFX WebView shell around the authenticated random-port loopback server. */
public class DesktopLauncher extends Application {
  private static final Logger LOGGER = LoggerFactory.getLogger(DesktopLauncher.class);
  private static final String VERSION = "0.8.0";
  private static final SecureRandom SESSION_RANDOM = new SecureRandom();
  private static volatile boolean windowSmokeTest;

  private LocalDesktopServer server;
  private SingleInstance instance;

  public static void main(String[] args) {
    MainFunctions.DesktopArguments parsed;
    try {
      parsed = MainFunctions.parse(args);
    } catch (IllegalArgumentException exception) {
      System.err.println(MainFunctions.safeError(exception));
      System.exit(2);
      return;
    }
    if (parsed.version()) {
      System.out.println(VERSION);
      return;
    }
    DesktopPaths paths = DesktopPaths.discover();
    MainFunctions.configureLogging(paths.logFile());
    if (parsed.healthCheck()) {
      try {
        healthCheck(paths);
        System.out.println("DESKTOP HEALTH CHECK: PASS version=" + VERSION);
      } catch (RuntimeException exception) {
        System.err.println(MainFunctions.safeError(exception));
        System.exit(1);
      }
      return;
    }
    if (parsed.providerCheck()) {
      try {
        providerCheck(paths);
        System.out.println(
            "PROVIDER CHECK: PASS agents=5 provider=deepseek model=deepseek-v4-pro reasoning=max");
      } catch (RuntimeException exception) {
        System.err.println(MainFunctions.safeError(exception));
        System.exit(1);
      }
      return;
    }
    windowSmokeTest = parsed.windowSmokeTest();
    launch(args);
  }

  @Override
  public void start(Stage stage) {
    DesktopPaths paths = DesktopPaths.discover();
    instance = new SingleInstance(paths.config().resolve("desktop.lock"));
    if (!instance.acquire()) {
      Platform.exit();
      return;
    }
    String token = newSessionToken();
    server = new LocalDesktopServer(paths, token);
    int port = server.start();
    String origin = "http://127.0.0.1:" + port;
    String startUrl =
        origin + "/?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

    WebView webView = new WebView();
    webView.setContextMenuEnabled(false);
    WebEngine engine = webView.getEngine();
    engine.setJavaScriptEnabled(true);
    engine.setCreatePopupHandler(configuration -> null);
    engine.setConfirmHandler(message -> showConfirmation(stage, message));
    webView.addEventFilter(
        MouseEvent.MOUSE_PRESSED,
        event -> {
          if (event.getButton() != MouseButton.SECONDARY) {
            return;
          }
          event.consume();
          if (engine.getLoadWorker().getState() != Worker.State.SUCCEEDED) {
            return;
          }
          try {
            engine.executeScript(contextMenuScript(event.getX(), event.getY()));
          } catch (RuntimeException exception) {
            LOGGER.debug("Web context-menu dispatch was unavailable");
          }
        });
    engine
        .locationProperty()
        .addListener(
            (observable, previous, next) -> {
              if (next != null && !allowedNavigation(origin, next)) {
                LOGGER.warn("Blocked non-local desktop navigation");
                Platform.runLater(() -> engine.load(origin + "/"));
              }
            });
    if (windowSmokeTest) {
      engine
          .getLoadWorker()
          .stateProperty()
          .addListener(
              (observable, previous, next) -> {
                if (next == Worker.State.SUCCEEDED) {
                  Platform.runLater(stage::close);
                }
              });
    }
    engine.load(startUrl);

    stage.setTitle("MathProofMesh");
    try (var icon =
        DesktopLauncher.class.getResourceAsStream(
            "/io/github/aililuola/mathproofmesh/desktop/assets/mathproofmesh.png")) {
      if (icon != null) {
        stage.getIcons().add(new Image(icon));
      }
    } catch (IOException exception) {
      LOGGER.warn("Desktop icon could not be loaded");
    }
    stage.setMinWidth(980);
    stage.setMinHeight(680);
    stage.setScene(new Scene(webView, 1440, 920));
    stage.setOnCloseRequest(event -> closeResources());
    if (!windowSmokeTest) {
      stage.show();
    } else {
      stage.setOpacity(0.0);
      stage.show();
    }
  }

  @Override
  public void stop() {
    closeResources();
  }

  static boolean allowedNavigation(String origin, String candidate) {
    try {
      URI allowed = URI.create(origin);
      URI requested = URI.create(candidate);
      return "http".equals(requested.getScheme())
          && "127.0.0.1".equals(requested.getHost())
          && requested.getPort() == allowed.getPort()
          && requested.getUserInfo() == null;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  static String contextMenuScript(double x, double y) {
    return "window.__mathproofmeshContextMenuAt("
        + contextCoordinate(x)
        + ","
        + contextCoordinate(y)
        + ")";
  }

  private static int contextCoordinate(double value) {
    if (!Double.isFinite(value)) {
      return 0;
    }
    return (int) Math.max(0, Math.min(100_000, Math.floor(value)));
  }

  private static boolean showConfirmation(Stage owner, String message) {
    ButtonType confirm = new ButtonType("\u786e\u8ba4", ButtonBar.ButtonData.OK_DONE);
    ButtonType cancel = new ButtonType("\u53d6\u6d88", ButtonBar.ButtonData.CANCEL_CLOSE);
    Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, message, confirm, cancel);
    dialog.initOwner(owner);
    dialog.setTitle("MathProofMesh");
    dialog.setHeaderText(null);
    return dialog.showAndWait().filter(confirm::equals).isPresent();
  }

  static void healthCheck(DesktopPaths paths) {
    String token = newSessionToken();
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_NONE);
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    try (LocalDesktopServer local = new LocalDesktopServer(paths, token)) {
      int port = local.start();
      URI bootstrap = URI.create("http://127.0.0.1:" + port + "/?token="
          + URLEncoder.encode(token, StandardCharsets.UTF_8));
      HttpResponse<String> first =
          client.send(
              HttpRequest.newBuilder(bootstrap).timeout(Duration.ofSeconds(15)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      if (first.statusCode() != 303) {
        throw new IllegalStateException("desktop bootstrap did not return a one-time redirect");
      }
      String cookie =
          first.headers().firstValue("set-cookie")
              .orElseThrow(() -> new IllegalStateException("desktop session cookie is missing"))
              .split(";", 2)[0];
      HttpResponse<String> index =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                  .timeout(Duration.ofSeconds(15))
                  .header("Cookie", cookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> api =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/api/bootstrap"))
                  .timeout(Duration.ofSeconds(15))
                  .header("Cookie", cookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (index.statusCode() != 200
          || !index.body().contains("MathProofMesh")
          || !index.body().contains("topology-view")
          || api.statusCode() != 200
          || !api.body().contains("\"version\":\"" + VERSION + "\"")
          || index.headers().firstValue("content-security-policy").isEmpty()) {
        throw new IllegalStateException("desktop HTTP/resource health check failed");
      }
    } catch (IOException exception) {
      throw new IllegalStateException("desktop health check I/O failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("desktop health check was interrupted", exception);
    }
  }

  static void providerCheck(DesktopPaths paths) {
    String token = newSessionToken();
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    try (LocalDesktopServer local = new LocalDesktopServer(paths, token)) {
      int port = local.start();
      URI bootstrap =
          URI.create(
              "http://127.0.0.1:"
                  + port
                  + "/?token="
                  + URLEncoder.encode(token, StandardCharsets.UTF_8));
      HttpResponse<String> first =
          client.send(
              HttpRequest.newBuilder(bootstrap).timeout(Duration.ofSeconds(15)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      if (first.statusCode() != 303) {
        throw new IllegalStateException("provider check bootstrap failed");
      }
      String cookie =
          first
              .headers()
              .firstValue("set-cookie")
              .orElseThrow(() -> new IllegalStateException("provider check session is missing"))
              .split(";", 2)[0];
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/api/probe"))
                  .timeout(Duration.ofSeconds(90))
                  .header("Cookie", cookie)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("provider check request failed");
      }
      validateProviderCheck(new ObjectMapper().readTree(response.body()));
    } catch (IOException exception) {
      throw new IllegalStateException("provider check I/O failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("provider check was interrupted", exception);
    }
  }

  static void validateProviderCheck(JsonNode response) {
    JsonNode results = response.path("results");
    if (!results.isArray() || results.size() != 5) {
      throw new IllegalStateException("provider check did not return five agents");
    }
    for (JsonNode result : results) {
      boolean valid =
          "deepseek".equals(result.path("provider").asText())
              && "deepseek-v4-pro".equals(result.path("model").asText())
              && "max".equals(result.path("reasoning_effort").asText())
              && result.path("credential_ok").asBoolean(false)
              && result.path("model_visible").asBoolean(false);
      if (!valid) {
        throw new IllegalStateException(
            "one or more DeepSeek credentials or model assignments failed validation");
      }
    }
  }

  private void closeResources() {
    if (server != null) {
      server.close();
      server = null;
    }
    if (instance != null) {
      instance.close();
      instance = null;
    }
  }

  private static String newSessionToken() {
    byte[] random = new byte[32];
    SESSION_RANDOM.nextBytes(random);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  }
}
