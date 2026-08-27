package io.github.aililuola.mathproofmesh.compatibility;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Explicit JUnit fixtures replacing pytest's implicit global fixture state.
 */
public final class MathProofMeshTestFixtures {
    private MathProofMeshTestFixtures() {
    }

    public static DemoConfigFixture demoConfig(Path temporaryRoot) {
        return new DemoConfigFixture(isolatedRunRoot(temporaryRoot), 2, 8_000, 8);
    }

    public static ArtifactStoreFixture artifactStore(Path temporaryRoot) {
        return new ArtifactStoreFixture(isolatedRunRoot(temporaryRoot), "test-run");
    }

    private static Path isolatedRunRoot(Path temporaryRoot) {
        Objects.requireNonNull(temporaryRoot, "temporaryRoot");
        return temporaryRoot.toAbsolutePath().normalize().resolve("runs");
    }

    public record DemoConfigFixture(
            Path runRoot,
            int neighborK,
            int maxContextChars,
            int maxVerifiedClaimsPerContext
    ) {
    }

    public record ArtifactStoreFixture(Path runRoot, String runId) {
        public Path runDirectory() {
            return runRoot.resolve(runId);
        }
    }
}
