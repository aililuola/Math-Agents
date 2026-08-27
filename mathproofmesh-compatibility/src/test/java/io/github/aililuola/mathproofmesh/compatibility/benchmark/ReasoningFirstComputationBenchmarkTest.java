package io.github.aililuola.mathproofmesh.compatibility.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationContext;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReasoningFirstComputationBenchmarkTest {

  @Test
  void reasoning_first_fixture_is_exact_deterministic_and_provider_free()
      throws Exception {
    Path root =
        Path.of(System.getProperty("mathproofmesh.projectRoot"))
            .toAbsolutePath()
            .normalize();
    Path baseline =
        root.resolve(
            "migration/baseline/auxiliary/benchmarks/reasoning_first_computation.py");
    String hash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(baseline)));
    assertEquals(
        "941d372eed641ff4c681ff3121d482a4532af800d208f1b9d7d318623f5154f3",
        hash);

    ComputationBroker broker =
        new ComputationBroker(
            "reasoning-first-benchmark",
            ComputationLimits.defaultsEnabled(),
            ComputationHandlerRegistry.javaOnly(),
            new InMemoryComputationCache());
    ExperimentSpec spec =
        new ExperimentSpec(
            object()
                .put("lhs", "x^5")
                .put("rhs", "x")
                .put("modulus", 5)
                .put("finite_reduction", true)
                .put(
                    "reduction_justification",
                    "The expression depends only on the residue class."),
            List.of("The complete residue domain is enumerated."),
            false,
            "Use only as evidence for the declared finite identity.",
            "Repair the arithmetic premise.",
            domains(),
            true,
            null,
            "reasoning-first-experiment",
            5,
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "Continue with a symbolic proof.",
            null,
            "reasoning-first-path",
            ComputationPurpose.CHECK_DERIVED_IDENTITY,
            "A concrete exact identity is ready for a finite check.",
            null,
            "phase-08-benchmark",
            object(),
            20260719,
            "Check the precise modular identity.",
            null,
            "Prevents unchecked arithmetic from entering the proof.");
    ComputationBroker.PreparedDecision prepared =
        broker.decide(spec, ComputationContext.initial(spec.pathId(), 1));
    ExperimentResult result =
        broker.runExperiment(prepared.spec(), prepared.decision());

    assertEquals(ExperimentOutcome.CERTIFIED, result.outcome());
    assertEquals(5, result.casesChecked());
    assertTrue(result.exactArithmetic());
    assertEquals(1, broker.ledger().usage(spec.pathId()).experiments());
  }

  private static ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static ObjectNode domains() {
    ObjectNode result = object();
    result.set("x", object().put("min", 0).put("max", 4));
    return result;
  }
}
