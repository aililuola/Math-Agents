package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class StrategyAuthorityCollectionOrderTest {
  @Test
  void strategyAuthoritySetsPreserveTheirPersistedIterationOrder() {
    LinkedHashSet<String> declaredOrder = new LinkedHashSet<>();
    for (int index = 19; index >= 0; index--) {
      declaredOrder.add("semantic-key-" + index);
    }
    List<String> expectedOrder = List.copyOf(declaredOrder);

    StrategyMechanismSignature signature =
        new StrategyMechanismSignature(
            "problem-hash",
            "root-hash",
            declaredOrder,
            declaredOrder,
            "domain-role",
            "GEOMETRIC",
            "dag-hash",
            "transform-hash",
            "falsification-hash",
            "structural-hash",
            true);
    StrategyPreflightReport report =
        new StrategyPreflightReport(
            "strategy-1",
            "problem-hash",
            List.of(),
            false,
            false,
            0.0d,
            declaredOrder,
            "report-hash");
    StrategyMechanismSnapshot mechanisms =
        new StrategyMechanismSnapshot(
            StrategyMechanismSnapshot.CURRENT_SCHEMA_VERSION,
            Map.of("strategy-1", signature),
            Map.of(
                "strategy-1",
                new StrategyMechanismProfile(
                    new LinkedHashSet<>(
                        List.of(
                            StrategyMechanismPrimitive.TRANSFORMATION,
                            StrategyMechanismPrimitive.ALGEBRAIC,
                            StrategyMechanismPrimitive.DIRECT)))),
            declaredOrder,
            1L);
    StrategyPreflightSnapshot preflights =
        new StrategyPreflightSnapshot(
            StrategyPreflightSnapshot.CURRENT_SCHEMA_VERSION,
            new LinkedHashMap<>(Map.of("strategy-1", report)),
            Map.of(),
            Map.of(),
            1L);

    assertThat(signature.targetCanonicalIds()).containsExactlyElementsOf(expectedOrder);
    assertThat(signature.requiredClaimSemanticKeys()).containsExactlyElementsOf(expectedOrder);
    assertThat(report.unresolvedRequiredClaimKeys()).containsExactlyElementsOf(expectedOrder);
    assertThat(mechanisms.legacyActiveStrategyIds()).containsExactlyElementsOf(expectedOrder);
    assertThat(mechanisms.profiles().get("strategy-1").primitives())
        .containsExactly(
            StrategyMechanismPrimitive.TRANSFORMATION,
            StrategyMechanismPrimitive.ALGEBRAIC,
            StrategyMechanismPrimitive.DIRECT);

    StrategyMechanismSnapshot restoredMechanisms =
        ContractObjectMapper.read(
            ContractObjectMapper.write(mechanisms), StrategyMechanismSnapshot.class);
    StrategyPreflightSnapshot restoredPreflights =
        ContractObjectMapper.read(
            ContractObjectMapper.write(preflights), StrategyPreflightSnapshot.class);
    assertThat(CanonicalJson.stableHash(restoredMechanisms))
        .isEqualTo(CanonicalJson.stableHash(mechanisms));
    assertThat(CanonicalJson.stableHash(restoredPreflights))
        .isEqualTo(CanonicalJson.stableHash(preflights));
  }
}
