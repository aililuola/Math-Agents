package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class RunStateNoProblemSpecificDependencyTest {
  @Test
  void runStateDomainHasNoProblemSpecificAuthorityDependency() {
    Class<?>[] types = {
      RunStateSnapshot.class,
      RunAuthoritySnapshot.class,
      RunStateEvidenceBundle.class,
      RunStateReconciler.class
    };
    String signatures =
        Arrays.stream(types)
            .flatMap(
                type ->
                    java.util.stream.Stream.concat(
                        Arrays.stream(type.getDeclaredFields()).map(Field::getGenericType),
                        type.isRecord()
                            ? Arrays.stream(type.getRecordComponents())
                                .map(RecordComponent::getGenericType)
                            : java.util.stream.Stream.empty()))
            .map(java.lang.reflect.Type::getTypeName)
            .reduce("", (left, right) -> left + " " + right);
    assertThat(signatures)
        .doesNotContain("NegativeKnowledge")
        .doesNotContain("ClaimLifecycle")
        .doesNotContain("SemanticPivot")
        .doesNotContain("ComputationExecutionLedger");
  }
}
