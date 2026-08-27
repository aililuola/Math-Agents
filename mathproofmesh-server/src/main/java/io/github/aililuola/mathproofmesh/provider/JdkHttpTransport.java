package io.github.aililuola.mathproofmesh.provider;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

public final class JdkHttpTransport implements HttpTransport {
  private final HttpClient client;

  public JdkHttpTransport() {
    this(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            // Match the authoritative Python httpx transport. Long-lived provider SSE
            // responses must not share HTTP/2 stream-reset behavior with sibling calls.
            .version(HttpClient.Version.HTTP_1_1)
            .build());
  }

  public JdkHttpTransport(HttpClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public HttpTransportResponse send(HttpTransportRequest request)
      throws IOException, InterruptedException {
    Objects.requireNonNull(request, "request");
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(request.uri())
            .timeout(request.timeout());
    request.headers().forEach(builder::header);
    if ("GET".equals(request.method())) {
      builder.GET();
    } else {
      builder.method(
          request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
    }
    HttpResponse<java.io.InputStream> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    return new HttpTransportResponse(
        response.statusCode(), response.headers().map(), response.body());
  }
}
