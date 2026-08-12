package io.github.aililuola.mathproofmesh.provider;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Objects;

/** Default-deny guard for network transports used by paid providers. */
public final class LiveProviderPolicy {
  private final boolean explicitlyEnabled;

  private LiveProviderPolicy(boolean explicitlyEnabled) {
    this.explicitlyEnabled = explicitlyEnabled;
  }

  public static LiveProviderPolicy disabled() {
    return new LiveProviderPolicy(false);
  }

  public static LiveProviderPolicy explicitlyEnabled() {
    return new LiveProviderPolicy(true);
  }

  public void authorize(URI endpoint, HttpTransport transport) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(transport, "transport");
    if (!transport.reachesNetwork() || isLoopback(endpoint)) {
      return;
    }
    if (!explicitlyEnabled) {
      throw ProviderException.liveCallDisabled();
    }
  }

  private static boolean isLoopback(URI endpoint) {
    String host = endpoint.getHost();
    if (host == null) {
      return false;
    }
    try {
      InetAddress[] addresses = InetAddress.getAllByName(host);
      return addresses.length > 0
          && java.util.Arrays.stream(addresses).allMatch(InetAddress::isLoopbackAddress);
    } catch (UnknownHostException exception) {
      return false;
    }
  }
}
