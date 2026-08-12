package io.github.aililuola.mathproofmesh.provider;

import io.github.aililuola.mathproofmesh.config.SecretValue;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

public final class DeepSeekProvider extends DeepSeekClient {
  public DeepSeekProvider(
      SecretValue apiKey,
      String model,
      URI baseUri,
      Duration timeout,
      Map<String, String> extraHeaders,
      HttpTransport transport,
      ProviderLimits limits,
      LiveProviderPolicy livePolicy) {
    super(
        apiKey,
        model,
        baseUri,
        timeout,
        extraHeaders,
        transport,
        limits,
        livePolicy);
  }
}
