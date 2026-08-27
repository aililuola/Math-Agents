package io.github.aililuola.mathproofmesh.computation;

import java.time.Duration;
import java.util.regex.Pattern;

/** Security settings for the optional arbitrary-program container boundary. */
public record SandboxSettings(
    boolean enabled,
    String image,
    Duration timeout,
    int memoryMb,
    double cpus,
    int pidsLimit,
    int maxOutputChars) {
  private static final Pattern PINNED_IMAGE =
      Pattern.compile("[^\\s@]+@sha256:[0-9a-f]{64}");

  public SandboxSettings {
    if (timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
      throw new IllegalArgumentException("sandbox timeout is invalid");
    }
    if (memoryMb < 32
        || memoryMb > 8_192
        || !Double.isFinite(cpus)
        || cpus < 0.1
        || cpus > 16.0
        || pidsLimit < 4
        || pidsLimit > 1_024
        || maxOutputChars < 256
        || maxOutputChars > 2_000_000) {
      throw new IllegalArgumentException("sandbox resource limits are invalid");
    }
    image = image == null ? "" : image.trim();
    if (enabled && !PINNED_IMAGE.matcher(image).matches()) {
      throw new IllegalArgumentException(
          "enabled sandbox image must be pinned by a lowercase sha256 digest");
    }
  }

  public static SandboxSettings disabled() {
    return new SandboxSettings(
        false, "", Duration.ofSeconds(20), 256, 1.0, 32, 20_000);
  }
}
