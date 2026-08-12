package io.github.aililuola.mathproofmesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithStructureTest {
    @Test
    void discoversAndVerifiesTheApplicationModules() {
        ApplicationModules modules = ApplicationModules.of(MathProofMeshApplication.class);

        assertTrue(modules.iterator().hasNext());
        modules.verify();
    }
}
