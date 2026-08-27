package io.github.aililuola.mathproofmesh.desktop.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/** Locates the single workspace-level benchmark protocol without hard-coding its Unicode name. */
public final class OlympiadProtocolDocumentLocator {
  private static final String ASCII_PREFIX = "MathProofMesh_";
  private static final String ASCII_MARKER = "benchmark_codex";

  private OlympiadProtocolDocumentLocator() {}

  public static Path locate(Path projectRoot) {
    Path project = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    Path publishDirectory = Objects.requireNonNull(project.getParent(), "projectRoot parent");
    Path workspace =
        Objects.requireNonNull(publishDirectory.getParent(), "workspace root")
            .toAbsolutePath()
            .normalize();
    if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)
        || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("benchmark project and workspace roots must exist");
    }
    try (Stream<Path> entries = Files.list(workspace)) {
      List<Path> matches =
          entries
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .filter(path -> !Files.isSymbolicLink(path))
              .filter(OlympiadProtocolDocumentLocator::matches)
              .map(path -> path.toAbsolutePath().normalize())
              .sorted()
              .toList();
      if (matches.size() != 1) {
        throw new IllegalStateException(
            "workspace must contain exactly one MathProofMesh Benchmark_Codex protocol document");
      }
      return matches.getFirst();
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark protocol document could not be located", exception);
    }
  }

  private static boolean matches(Path path) {
    String name = Objects.requireNonNull(path.getFileName(), "protocol file name").toString();
    String lower = name.toLowerCase(Locale.ROOT);
    return name.startsWith(ASCII_PREFIX)
        && lower.contains(ASCII_MARKER)
        && lower.endsWith(".md");
  }
}
