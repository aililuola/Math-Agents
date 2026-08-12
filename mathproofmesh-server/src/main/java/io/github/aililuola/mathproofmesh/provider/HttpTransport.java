package io.github.aililuola.mathproofmesh.provider;

import java.io.IOException;

@FunctionalInterface
public interface HttpTransport {
  HttpTransportResponse send(HttpTransportRequest request)
      throws IOException, InterruptedException;

  default boolean reachesNetwork() {
    return true;
  }
}
