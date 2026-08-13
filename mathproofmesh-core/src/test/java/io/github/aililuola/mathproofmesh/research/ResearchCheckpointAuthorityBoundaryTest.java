package io.github.aililuola.mathproofmesh.research;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ResearchCheckpointAuthorityBoundaryTest {
  @Test
  void publicResearchTypesContainNoAuthorityGrantingLiteral() {
    assertThat(Arrays.stream(ResearchFindingKind.values()).map(Enum::name))
        .noneMatch(name -> name.contains("VERIFIED") || name.contains("FACT") || name.contains("PROVED"));
    assertThat(Arrays.stream(ResearchFindingStatus.values()).map(Enum::name))
        .noneMatch(name -> name.contains("VERIFIED") || name.contains("FACT") || name.contains("PROVED"));
  }
}
