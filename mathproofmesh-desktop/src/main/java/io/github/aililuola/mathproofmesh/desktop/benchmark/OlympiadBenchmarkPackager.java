package io.github.aililuola.mathproofmesh.desktop.benchmark;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the local-only sanitized external-audit ZIP from an allowlisted campaign projection. */
public final class OlympiadBenchmarkPackager {
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private OlympiadBenchmarkPackager() {}

  public static PackageResult create(
      Path projectRoot,
      Path benchmarkRoot,
      Path campaignRoot,
      Path protocolDocument,
      Path packageDirectory,
      OlympiadSecretRedactor redactor) {
    return create(
        projectRoot,
        benchmarkRoot,
        campaignRoot,
        protocolDocument,
        packageDirectory,
        redactor,
        Clock.systemUTC());
  }

  static PackageResult create(
      Path projectRoot,
      Path benchmarkRoot,
      Path campaignRoot,
      Path protocolDocument,
      Path packageDirectory,
      OlympiadSecretRedactor redactor,
      Clock clock) {
    Path project = directory(projectRoot, "projectRoot");
    Path benchmark = directory(benchmarkRoot, "benchmarkRoot");
    Path campaign = directory(campaignRoot, "campaignRoot");
    Path protocol = file(protocolDocument, "protocolDocument");
    Path packages = normalize(packageDirectory, "packageDirectory");
    Objects.requireNonNull(redactor, "redactor");
    Objects.requireNonNull(clock, "clock");
    String head = OlympiadGitExecutionState.readHead(project);
    String stamp = STAMP.format(clock.instant());
    String baseName =
        "MathProofMesh_olympiad-5key-v1_" + head.substring(0, 12) + '_' + stamp;
    Path staging = packages.resolve(baseName).normalize();
    if (!staging.startsWith(packages) || Files.exists(staging)) {
      throw new IllegalStateException("benchmark package staging path is not fresh");
    }
    copyFile(benchmark.resolve("README.md"), staging.resolve("README.md"));
    copyFile(
        benchmark.resolve("benchmark-manifest.yaml"),
        staging.resolve("benchmark-manifest.yaml"));
    copyTree(benchmark.resolve("schemas"), staging.resolve("schemas"));
    copyTree(benchmark.resolve("problems"), staging.resolve("problems"));
    copyFile(protocol, staging.resolve("benchmark-protocol-v1.0.md"));
    copyTree(campaign.resolve("runs"), staging.resolve("results/runs"));
    copyTree(campaign.resolve("aggregate"), staging.resolve("results/aggregate"));
    if (Files.isDirectory(campaign.resolve("preflight"), LinkOption.NOFOLLOW_LINKS)) {
      copyTree(campaign.resolve("preflight"), staging.resolve("results/preflight"));
    }

    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("benchmark_id", OlympiadBenchmarkPlan.BENCHMARK_ID);
    manifest.put("baseline_commit", OlympiadBenchmarkPlan.BASELINE_COMMIT);
    manifest.put("benchmark_head", head);
    manifest.put("created_at", clock.instant().toString());
    manifest.put("real_provider", true);
    manifest.put("external_scores_pending", true);
    manifest.put("hidden_reasoning_included", false);
    manifest.put("raw_credentials_included", false);
    write(staging.resolve("MANIFEST.json"), ContractObjectMapper.write(manifest) + "\n");
    int checksummedFiles = OlympiadBundleChecksums.write(staging);
    OlympiadSecretRedactor.LeakReport leaks = redactor.scan(staging);
    if (!leaks.passed() || !OlympiadBundleChecksums.verify(staging).passed()) {
      throw new IllegalStateException("benchmark package failed redaction or checksum validation");
    }
    Path zip = packages.resolve(baseName + ".zip").normalize();
    zipTree(staging, zip);
    return new PackageResult(zip, staging, checksummedFiles, leaks.filesScanned());
  }

  private static void copyTree(Path source, Path destination) {
    Path root = directory(source, "source");
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted().toList()) {
        Path relative = root.relativize(path);
        Path target = destination.resolve(relative).normalize();
        if (!target.startsWith(destination.toAbsolutePath().normalize())) {
          throw new IllegalStateException("benchmark package path escaped staging");
        }
        if (Files.isSymbolicLink(path)) {
          throw new IllegalStateException("benchmark package may not include symbolic links");
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          Files.createDirectories(target);
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          copyFile(path, target);
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark package tree could not be copied", exception);
    }
  }

  private static void copyFile(Path source, Path destination) {
    Path input = file(source, "source");
    try {
      Files.createDirectories(Objects.requireNonNull(destination.getParent(), "destination parent"));
      Files.copy(input, destination, StandardCopyOption.COPY_ATTRIBUTES);
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark package file could not be copied", exception);
    }
  }

  private static void write(Path destination, String value) {
    try {
      Files.createDirectories(Objects.requireNonNull(destination.getParent(), "destination parent"));
      Files.writeString(destination, value, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark package manifest could not be written", exception);
    }
  }

  private static void zipTree(Path staging, Path zip) {
    try {
      Files.createDirectories(Objects.requireNonNull(zip.getParent(), "zip parent"));
      try (OutputStream output = Files.newOutputStream(zip);
          ZipOutputStream archive = new ZipOutputStream(output, StandardCharsets.UTF_8);
          Stream<Path> paths = Files.walk(staging)) {
        List<Path> files =
            paths
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing(path -> staging.relativize(path).toString()))
                .toList();
        byte[] buffer = new byte[64 * 1024];
        for (Path path : files) {
          String name = staging.relativize(path).toString().replace('\\', '/');
          ZipEntry entry = new ZipEntry(name);
          entry.setTime(0L);
          archive.putNextEntry(entry);
          try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
              archive.write(buffer, 0, read);
            }
          }
          archive.closeEntry();
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark ZIP could not be created", exception);
    }
  }

  private static Path directory(Path path, String field) {
    Path normalized = normalize(path, field);
    if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(field + " must be an existing directory");
    }
    return normalized;
  }

  private static Path file(Path path, String field) {
    Path normalized = normalize(path, field);
    if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(normalized)) {
      throw new IllegalArgumentException(field + " must be a regular non-link file");
    }
    return normalized;
  }

  private static Path normalize(Path path, String field) {
    return Objects.requireNonNull(path, field).toAbsolutePath().normalize();
  }

  public record PackageResult(
      Path zip, Path stagingDirectory, int checksummedFiles, int scannedFiles) {
    public PackageResult {
      zip = normalize(zip, "zip");
      stagingDirectory = normalize(stagingDirectory, "stagingDirectory");
      if (checksummedFiles < 1 || scannedFiles < 1) {
        throw new IllegalArgumentException("package file counters must be positive");
      }
    }
  }
}
