package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;
import org.junit.jupiter.api.Test;

class DesktopBrokerSemanticDedupeCollisionBlackBoxTest {
  @Test
  void orderedQuantifiersBindingsScopeAndPolarityParticipateInDedupe() {
    DesktopBrokerLegacyBlackBoxFixture fixture = new DesktopBrokerLegacyBlackBoxFixture();
    VariableBinding x = new VariableBinding(List.of(), "x", "D", "claim", "x");
    var forall = List.of(new QuantifierSpec("x", "D", "forall", 0, List.of(), "x"));
    var exists = List.of(new QuantifierSpec("x", "D", "exists", 0, List.of(), "x"));
    fixture.broker.publish(fixture.fact("forall-p", forall, List.of(x), List.of("D"), "positive"), "referee-a", 0);
    fixture.broker.publish(fixture.fact("exists-p", exists, List.of(x), List.of("D"), "positive"), "referee-a", 0);
    int actual = fixture.repository.snapshot().messages().size();
    System.out.println("SEMANTICALLY_DISTINCT_MESSAGES=2");
    System.out.println("BROKER_RECORDS_EXPECTED=2");
    System.out.println("BROKER_RECORDS_ACTUAL=" + actual);
    assertThat(actual).isEqualTo(2);
  }
}
