package io.github.aililuola.mathproofmesh.desktop.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** Immutable Git execution identity captured before a benchmark campaign starts. */
public record OlympiadGitExecutionState(String branch, String head, boolean dirty) {
  public static final String DIRTY_ENV = "BENCHMARK_EXECUTION_GIT_DIRTY";

  public OlympiadGitExecutionState {
    branch = require(branch, "branch");
    head = require(head, "head").toLowerCase(Locale.ROOT);
    if (!head.matches("[0-9a-f]{40}")) {
      throw new IllegalArgumentException("head must be a full Git commit id");
    }
  }

  public static OlympiadGitExecutionState capture(Path projectRoot) {
    return capture(projectRoot, System::getenv);
  }

  static OlympiadGitExecutionState capture(
      Path projectRoot, Function<String, String> environment) {
    Path project = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    GitIdentity identity = readIdentity(project);
    String configuredDirty =
        Objects.requireNonNull(environment, "environment").apply(DIRTY_ENV);
    if (configuredDirty == null) {
      throw new IllegalStateException(DIRTY_ENV + " must be set before benchmark launch");
    }
    String dirtyValue = require(configuredDirty, DIRTY_ENV).toLowerCase(Locale.ROOT);
    if (!List.of("true", "false").contains(dirtyValue)) {
      throw new IllegalArgumentException(DIRTY_ENV + " must be true or false");
    }
    return new OlympiadGitExecutionState(
        identity.branch(), identity.head(), Boolean.parseBoolean(dirtyValue));
  }

  static String readHead(Path projectRoot) {
    return readIdentity(projectRoot).head();
  }

  private static GitIdentity readIdentity(Path projectRoot) {
    Path project = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    Path gitDirectory = resolveGitDirectory(project);
    try {
      String headValue =
          require(
              Files.readString(gitDirectory.resolve("HEAD"), StandardCharsets.US_ASCII),
              "Git HEAD");
      if (!headValue.startsWith("ref:")) {
        return new GitIdentity("HEAD", requireCommit(headValue));
      }
      String reference = require(headValue.substring("ref:".length()), "Git HEAD reference");
      String branch = reference.startsWith("refs/heads/")
          ? reference.substring("refs/heads/".length())
          : reference;
      Path commonDirectory = resolveCommonDirectory(gitDirectory);
      return new GitIdentity(branch, resolveReference(gitDirectory, commonDirectory, reference));
    } catch (IOException exception) {
      throw new IllegalStateException("Git execution identity could not be read", exception);
    }
  }

  private static Path resolveGitDirectory(Path project) {
    Path dotGit = project.resolve(".git");
    if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
      return dotGit;
    }
    if (!Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("projectRoot must contain Git metadata");
    }
    try {
      String pointer = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
      if (!pointer.startsWith("gitdir:")) {
        throw new IllegalStateException("unsupported Git worktree pointer");
      }
      Path target = Path.of(pointer.substring("gitdir:".length()).strip());
      return (target.isAbsolute() ? target : project.resolve(target)).normalize();
    } catch (IOException exception) {
      throw new IllegalStateException("Git worktree pointer could not be read", exception);
    }
  }

  private static Path resolveCommonDirectory(Path gitDirectory) throws IOException {
    Path marker = gitDirectory.resolve("commondir");
    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
      return gitDirectory;
    }
    Path value = Path.of(Files.readString(marker, StandardCharsets.US_ASCII).strip());
    return (value.isAbsolute() ? value : gitDirectory.resolve(value)).normalize();
  }

  private static String resolveReference(
      Path gitDirectory, Path commonDirectory, String reference) throws IOException {
    for (Path root : List.of(gitDirectory, commonDirectory)) {
      Path loose = root.resolve(reference).normalize();
      if (loose.startsWith(root) && Files.isRegularFile(loose, LinkOption.NOFOLLOW_LINKS)) {
        return requireCommit(Files.readString(loose, StandardCharsets.US_ASCII));
      }
    }
    Path packed = commonDirectory.resolve("packed-refs");
    if (Files.isRegularFile(packed, LinkOption.NOFOLLOW_LINKS)) {
      for (String line : Files.readAllLines(packed, StandardCharsets.US_ASCII)) {
        if (!line.startsWith("#") && !line.startsWith("^") && line.endsWith(" " + reference)) {
          return requireCommit(line.substring(0, line.indexOf(' ')));
        }
      }
    }
    throw new IllegalStateException("Git HEAD reference could not be resolved");
  }

  private static String requireCommit(String value) {
    String commit = require(value, "Git commit").toLowerCase(Locale.ROOT);
    if (!commit.matches("[0-9a-f]{40}")) {
      throw new IllegalStateException("Git HEAD is not a full commit id");
    }
    return commit;
  }

  private static String require(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return normalized;
  }

  private record GitIdentity(String branch, String head) {}
}
