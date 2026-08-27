package io.github.aililuola.mathproofmesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VersionInfoTest {
    @Test
    void exposesSourceAndTargetVersionsWithoutImportSideEffects() {
        assertEquals("0.8.2", VersionInfo.SOURCE_PYTHON_VERSION);
        assertEquals("0.8.0", VersionInfo.TARGET_RELEASE_VERSION);
    }
}
