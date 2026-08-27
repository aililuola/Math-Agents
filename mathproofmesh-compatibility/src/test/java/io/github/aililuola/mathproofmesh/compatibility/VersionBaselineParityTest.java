package io.github.aililuola.mathproofmesh.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aililuola.mathproofmesh.VersionInfo;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VersionBaselineParityTest {
    @Test
    void versionMetadataMatchesTheFrozenPythonBaseline() throws IOException {
        Path projectRoot = Path.of(System.getProperty("mathproofmesh.projectRoot"));
        Path baselinePath = projectRoot.resolve("migration").resolve("BASELINE.json");
        JsonNode baseline = new ObjectMapper().readTree(baselinePath.toFile());

        assertEquals(
                VersionInfo.SOURCE_PYTHON_VERSION,
                baseline.path("python_project").path("version").asText()
        );
        assertEquals(759, baseline.path("planned_python_tests").asInt());
        assertEquals("0.8.0", VersionInfo.TARGET_RELEASE_VERSION);
    }
}
