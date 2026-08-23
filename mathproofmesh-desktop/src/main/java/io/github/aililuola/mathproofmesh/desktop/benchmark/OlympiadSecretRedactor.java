package io.github.aililuola.mathproofmesh.desktop.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Removes known credentials and rejects credential-shaped output without revealing matches. */
public final class OlympiadSecretRedactor {
  private static final Pattern AUTHORIZATION =
      Pattern.compile("(?i)authorization\\s*[:=]\\s*(?:bearer\\s+)?[^\\s\\\",}]+");
  private static final Pattern CREDENTIAL = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{16,}\\b");

  private final List<String> secrets;

  public OlympiadSecretRedactor(List<String> secrets) {
    this.secrets =
        List.copyOf(Objects.requireNonNull(secrets, "secrets")).stream()
            .filter(secret -> secret != null && !secret.isBlank())
            .toList();
  }

  public String sanitize(String value) {
    String result = Objects.requireNonNull(value, "value");
    for (String secret : secrets) {
      result = result.replace(secret, "[REDACTED]");
    }
    result = AUTHORIZATION.matcher(result).replaceAll("[REDACTED_AUTHORIZATION_HEADER]");
    return CREDENTIAL.matcher(result).replaceAll("[REDACTED]");
  }

  public LeakReport scan(Path root) {
    Path normalized = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    AtomicInteger files = new AtomicInteger();
    AtomicInteger exact = new AtomicInteger();
    AtomicInteger authorization = new AtomicInteger();
    AtomicInteger shaped = new AtomicInteger();
    try (Stream<Path> paths = Files.walk(normalized)) {
      paths.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
          .filter(path -> path.toAbsolutePath().normalize().startsWith(normalized))
          .forEach(
              path -> {
                files.incrementAndGet();
                String text = readText(path);
                for (String secret : secrets) {
                  if (text.contains(secret)) {
                    exact.incrementAndGet();
                  }
                }
                authorization.addAndGet(matches(AUTHORIZATION, text));
                shaped.addAndGet(matches(CREDENTIAL, text));
              });
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark secret scan failed", exception);
    }
    return new LeakReport(files.get(), exact.get(), authorization.get(), shaped.get());
  }

  private static String readText(Path path) {
    try {
      byte[] bytes = Files.readAllBytes(path);
      if (bytes.length > 32 * 1024 * 1024) {
        throw new IllegalStateException("benchmark evidence file exceeds scan limit");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark evidence file could not be scanned", exception);
    }
  }

  private static int matches(Pattern pattern, String text) {
    int count = 0;
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  public record LeakReport(
      int filesScanned,
      int secretLeaks,
      int authorizationHeaderLeaks,
      int credentialPatternLeaks) {
    public LeakReport {
      if (filesScanned < 0
          || secretLeaks < 0
          || authorizationHeaderLeaks < 0
          || credentialPatternLeaks < 0) {
        throw new IllegalArgumentException("leak report counters must not be negative");
      }
    }

    public boolean passed() {
      return secretLeaks == 0 && authorizationHeaderLeaks == 0 && credentialPatternLeaks == 0;
    }
  }
}
