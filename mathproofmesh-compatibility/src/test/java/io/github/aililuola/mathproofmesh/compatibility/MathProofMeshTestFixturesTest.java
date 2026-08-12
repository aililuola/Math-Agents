package io.github.aililuola.mathproofmesh.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MathProofMeshTestFixturesTest {
    @TempDir
    Path temporaryRoot;

    @Test
    void demoConfigurationMatchesTheFrozenPytestFixture() {
        MathProofMeshTestFixtures.DemoConfigFixture fixture =
                MathProofMeshTestFixtures.demoConfig(temporaryRoot);

        assertEquals(2, fixture.neighborK());
        assertEquals(8_000, fixture.maxContextChars());
        assertEquals(8, fixture.maxVerifiedClaimsPerContext());
        assertTrue(fixture.runRoot().startsWith(temporaryRoot));
    }

    @Test
    void artifactStoreFixtureIsRunScopedAndTemporary() {
        MathProofMeshTestFixtures.ArtifactStoreFixture fixture =
                MathProofMeshTestFixtures.artifactStore(temporaryRoot);

        assertEquals("test-run", fixture.runId());
        assertEquals(fixture.runRoot().resolve("test-run"), fixture.runDirectory());
        assertTrue(fixture.runDirectory().startsWith(temporaryRoot));
    }
}
