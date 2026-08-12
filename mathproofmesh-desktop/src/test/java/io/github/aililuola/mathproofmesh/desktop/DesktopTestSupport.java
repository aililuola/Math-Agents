package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aililuola.mathproofmesh.api.ApiObservability;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.RunApiService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

final class DesktopTestSupport {
  static final ObjectMapper MAPPER = new ObjectMapper();
  static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";

  private DesktopTestSupport() {}

  static DesktopPaths paths(Path root) {
    return DesktopPaths.discover(root);
  }

  static CredentialVault vault(DesktopPaths paths) {
    return new CredentialVault(paths.credentialsFile(), MAPPER, new ReversingProtector());
  }

  static DesktopRunManager manager(DesktopPaths paths) {
    return manager(paths, null);
  }

  static DesktopRunManager manager(DesktopPaths paths, RunExecutionBackend backend) {
    CredentialVault vault = vault(paths);
    SettingsStore settings = new SettingsStore(paths.settingsFile(), MAPPER);
    DesktopConfigService configs = new DesktopConfigService(paths, vault);
    RunRepository repository = new RunRepository(paths, MAPPER);
    RunApiService runs =
        new RunApiService(
            new ApiObservability(new SimpleMeterRegistry()), paths.runs().toString(), 2, backend);
    return new DesktopRunManager(paths, settings, vault, configs, repository, runs);
  }

  static Map<String, Object> json(String body) throws IOException {
    return MAPPER.readValue(body, new TypeReference<>() {});
  }

  static final class HttpSession implements AutoCloseable {
    private final LocalDesktopServer server;
    private final HttpClient client;
    private final String cookie;
    private final String setCookie;
    private final int port;

    HttpSession(DesktopPaths paths) throws IOException, InterruptedException {
      server = new LocalDesktopServer(paths, TOKEN);
      port = server.start();
      client =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(10))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      URI bootstrap =
          uri("/?token=" + URLEncoder.encode(TOKEN, StandardCharsets.UTF_8));
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(bootstrap).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 303) {
        server.close();
        throw new IllegalStateException("desktop bootstrap failed: " + response.statusCode());
      }
      setCookie =
          response
              .headers()
              .firstValue("set-cookie")
              .orElseThrow(() -> new IllegalStateException("session cookie missing"));
      cookie = setCookie.split(";", 2)[0];
    }

    int port() {
      return port;
    }

    String setCookie() {
      return setCookie;
    }

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
      return send(path, "GET", null, true);
    }

    HttpResponse<String> getWithoutCookie(String path) throws IOException, InterruptedException {
      return send(path, "GET", null, false);
    }

    HttpResponse<String> put(String path, String body) throws IOException, InterruptedException {
      return send(path, "PUT", body, true);
    }

    HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
      return send(path, "POST", body, true);
    }

    private HttpResponse<String> send(String path, String method, String body, boolean authenticated)
        throws IOException, InterruptedException {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20));
      if (authenticated) {
        request.header("Cookie", cookie);
      }
      if (body != null) {
        request.header("Content-Type", "application/json");
        request.method(method, HttpRequest.BodyPublishers.ofString(body));
      } else {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      }
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
      return URI.create("http://127.0.0.1:" + port + path);
    }

    @Override
    public void close() {
      server.close();
    }
  }

  private static final class ReversingProtector implements SecretProtector {
    @Override
    public byte[] protect(byte[] plaintext) {
      byte[] result = plaintext.clone();
      reverse(result);
      return result;
    }

    @Override
    public byte[] unprotect(byte[] ciphertext) {
      byte[] result = ciphertext.clone();
      reverse(result);
      return result;
    }

    @Override
    public String protectionId() {
      return "test-reversing-protector";
    }

    @Override
    public String entropyId() {
      return "test-entropy-v1";
    }

    private static void reverse(byte[] value) {
      for (int left = 0, right = value.length - 1; left < right; left++, right--) {
        byte temporary = value[left];
        value[left] = value[right];
        value[right] = temporary;
      }
    }
  }
}
