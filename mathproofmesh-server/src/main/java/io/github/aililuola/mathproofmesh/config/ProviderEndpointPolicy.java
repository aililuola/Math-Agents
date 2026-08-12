package io.github.aililuola.mathproofmesh.config;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Administrator-owned provider endpoint policy.
 *
 * <p>Endpoints are checked again after DNS resolution so an allowed hostname
 * cannot silently rebind to an internal address.
 */
public final class ProviderEndpointPolicy {
  private static final Set<String> DEFAULT_ALLOWED_HOSTS =
      Set.of(
          "api.openai.com",
          "api.deepseek.com",
          "api.anthropic.com",
          "generativelanguage.googleapis.com");

  private final Set<String> allowedHosts;
  private final HostResolver resolver;

  public ProviderEndpointPolicy() {
    this(DEFAULT_ALLOWED_HOSTS, ProviderEndpointPolicy::resolveSystem);
  }

  public ProviderEndpointPolicy(Set<String> allowedHosts, HostResolver resolver) {
    Objects.requireNonNull(allowedHosts, "allowedHosts");
    this.allowedHosts =
        allowedHosts.stream()
            .map(ProviderEndpointPolicy::normalizeHost)
            .collect(Collectors.toUnmodifiableSet());
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  public URI endpointForRequest(
      AgentConfig agent, String userBaseUrlOverride, boolean developmentMode) {
    Objects.requireNonNull(agent, "agent");
    if (userBaseUrlOverride != null && !userBaseUrlOverride.isBlank()) {
      throw new ConfigValidationException(
          "provider base URL overrides are administrator-only");
    }
    if (agent.baseUrl() == null || agent.baseUrl().isBlank()) {
      return null;
    }
    URI endpoint = parse(agent.baseUrl());
    validate(endpoint, agent.provider(), developmentMode);
    return endpoint;
  }

  public URI validateRedirect(
      URI original, URI redirect, String provider, boolean developmentMode) {
    Objects.requireNonNull(original, "original");
    Objects.requireNonNull(redirect, "redirect");
    validate(original, provider, developmentMode);
    URI resolved = original.resolve(redirect);
    String originalHost = normalizeHost(requireHost(original));
    String redirectHost = normalizeHost(requireHost(resolved));
    if (!originalHost.equals(redirectHost)
        || effectivePort(original) != effectivePort(resolved)
        || !asciiLowercase(original.getScheme())
            .equals(asciiLowercase(resolved.getScheme()))) {
      throw new ConfigValidationException("cross-host provider redirects are forbidden");
    }
    validate(resolved, provider, developmentMode);
    return resolved;
  }

  private void validate(URI endpoint, String provider, boolean developmentMode) {
    if (!endpoint.isAbsolute()
        || endpoint.getRawUserInfo() != null
        || endpoint.getRawFragment() != null
        || endpoint.getRawQuery() != null) {
      throw new ConfigValidationException("provider endpoint must be an absolute base URI");
    }
    String host = normalizeHost(requireHost(endpoint));
    String scheme = asciiLowercase(endpoint.getScheme());
    boolean developmentMock =
        developmentMode
            && "mock".equals(provider)
            && ("http".equals(scheme) || "https".equals(scheme));
    if (developmentMock) {
      if (!isLoopbackOnly(host)) {
        throw new ConfigValidationException(
            "development mock endpoints must resolve only to loopback addresses");
      }
      return;
    }
    if (!"https".equals(scheme)) {
      throw new ConfigValidationException("production provider endpoints require HTTPS");
    }
    if (!allowedHosts.contains(host)) {
      throw new ConfigValidationException(
          "provider endpoint host is not in the administrator allowlist");
    }
    for (InetAddress address : resolve(host)) {
      if (!isPublicAddress(address)) {
        throw new ConfigValidationException(
            "provider endpoint resolved to a non-public address");
      }
    }
  }

  private boolean isLoopbackOnly(String host) {
    List<InetAddress> addresses = resolve(host);
    return !addresses.isEmpty() && addresses.stream().allMatch(InetAddress::isLoopbackAddress);
  }

  private List<InetAddress> resolve(String host) {
    try {
      List<InetAddress> addresses = List.copyOf(resolver.resolve(host));
      if (addresses.isEmpty()) {
        throw new ConfigValidationException("provider endpoint did not resolve");
      }
      return addresses;
    } catch (UnknownHostException exception) {
      throw new ConfigValidationException("provider endpoint could not be resolved", exception);
    }
  }

  private static URI parse(String value) {
    try {
      return new URI(value);
    } catch (URISyntaxException exception) {
      throw new ConfigValidationException("provider endpoint is not a valid URI", exception);
    }
  }

  private static String requireHost(URI uri) {
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new ConfigValidationException("provider endpoint must include a host");
    }
    return host;
  }

  private static String normalizeHost(String host) {
    String value = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    return asciiLowercase(value);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equals(asciiLowercase(uri.getScheme())) ? 443 : 80;
  }

  private static String asciiLowercase(String value) {
    if (value == null) {
      throw new ConfigValidationException("provider endpoint must include a scheme");
    }
    StringBuilder normalized = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character >= 'A' && character <= 'Z') {
        normalized.append((char) (character + ('a' - 'A')));
      } else if (character <= 0x7f) {
        normalized.append(character);
      } else {
        throw new ConfigValidationException(
            "provider endpoint scheme and host must normalize to ASCII");
      }
    }
    return normalized.toString();
  }

  private static List<InetAddress> resolveSystem(String host) throws UnknownHostException {
    return List.of(InetAddress.getAllByName(host));
  }

  private static boolean isPublicAddress(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      if (first == 0
          || first == 10
          || first == 127
          || first >= 224
          || first == 169 && second == 254
          || first == 172 && second >= 16 && second <= 31
          || first == 192 && second == 168
          || first == 100 && second >= 64 && second <= 127
          || first == 198 && (second == 18 || second == 19)) {
        return false;
      }
      return !(first == 192 && second == 0)
          && !(first == 192 && second == 2)
          && !(first == 198 && second == 51)
          && !(first == 203 && second == 0);
    }
    if (address instanceof Inet6Address) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      boolean uniqueLocal = (first & 0xfe) == 0xfc;
      boolean documentation = first == 0x20 && second == 0x01
          && Byte.toUnsignedInt(bytes[2]) == 0x0d
          && Byte.toUnsignedInt(bytes[3]) == 0xb8;
      return !uniqueLocal && !documentation;
    }
    return false;
  }

  @FunctionalInterface
  public interface HostResolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;
  }
}
