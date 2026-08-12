package io.github.aililuola.mathproofmesh;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        classes = MathProofMeshApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MathProofMeshApplicationIT {
    @Test
    void startsAFrameworkContextWithoutOpeningABusinessEndpoint(
            @Autowired ApplicationContext context
    ) {
        assertNotNull(context);
        assertNotNull(context.getBean(MathProofMeshApplication.class));
    }
}
