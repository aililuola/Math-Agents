package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Phase17ContractValidationBranchTest {

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("contractCases")
  void everyTypedContractFailsClosedAndDeterministically(
      ComputationMethod method, String arguments, String domains) {
    var spec = ComputationFixtures.spec(method, arguments, domains);

    List<String> first = ContractsFunctions.validateExperimentContract(spec);
    List<String> second = ContractsFunctions.validateExperimentContract(spec);

    assertThat(first).isEqualTo(second);
    assertThat(first).allSatisfy(issue -> assertThat(issue).isNotBlank());
  }

  @Test
  void catalogFilteringAndGreedyDiscoveryNormalizationCoverControlBranches() {
    assertThat(ContractsFunctions.experimentToolCatalog(null)).hasSize(15);
    assertThat(ContractsFunctions.experimentToolCatalog(Set.of())).hasSize(15);
    assertThat(ContractsFunctions.experimentToolCatalog(Set.of("bounded_greedy_sequence")))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.path("method").asText()).isEqualTo("bounded_greedy_sequence");
              assertThat(item.path("allowed_rules")).hasSize(4);
            });
    assertThat(ContractsFunctions.experimentToolCatalog(Set.of("not-a-tool"))).isEmpty();

    var unclaimed =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[1],\"length\":3,\"rule\":\"coprime_to_all\"}");
    var normalized = ContractsFunctions.normalizeExploratoryContract(unclaimed);
    assertThat(normalized.changed()).isTrue();
    assertThat(normalized.spec().purpose()).isEqualTo(ComputationPurpose.DISCOVER_PATTERN);
    assertThat(ContractsFunctions.computationContractMode(normalized.spec()))
        .isEqualTo(ContractsFunctions.ContractMode.DISCOVERY);
    assertThat(ContractsFunctions.normalizeExploratoryContract(normalized.spec()).changed()).isFalse();
    assertThat(
            ContractsFunctions.normalizeExploratoryContract(
                    ComputationFixtures.spec(
                        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
                        "{\"initial_values\":[1],\"length\":3,\"rule\":\"coprime_to_all\","
                            + "\"claimed_values\":[1,2,3]}"))
                .changed())
        .isFalse();
    assertThat(
            ContractsFunctions.normalizeExploratoryContract(
                    ComputationFixtures.spec(
                        ComputationMethod.SYMPY_SIMPLIFY, "{\"expression\":\"x+x\"}"))
                .changed())
        .isFalse();
  }

  private static Stream<Arguments> contractCases() {
    return Stream.of(
        c(ComputationMethod.SYMPY_SIMPLIFY, "{}", "{}"),
        c(ComputationMethod.SYMPY_SIMPLIFY, "{\"expression\":1,\"extra\":true}", "{\"x\":[1]}"),
        c(ComputationMethod.SYMPY_SIMPLIFY, "{\"expression\":\" \"}", "{}"),
        c(ComputationMethod.SYMPY_SIMPLIFY, "{\"expression\":\"x+x\"}", "{}"),
        c(ComputationMethod.POLYNOMIAL_FACTOR, "{\"expression\":\"x^2-1\"}", "{}"),
        c(ComputationMethod.SYMPY_EQUIVALENT, "{}", "{}"),
        c(ComputationMethod.SYMPY_EQUIVALENT, "{\"lhs\":\"x\",\"rhs\":\"x\"}", "{}"),
        c(ComputationMethod.NUMERIC_COUNTEREXAMPLE, "{}", "{}"),
        c(
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            "{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"bad\",\"variables\":null,"
                + "\"ranges\":null}",
            "{}"),
        c(
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            "{\"lhs\":\"x\",\"rhs\":\"0\",\"variables\":\"x\",\"ranges\":[],"
                + "\"samples\":true,\"tolerance\":true}",
            "{}"),
        c(
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            "{\"lhs\":\"x\",\"rhs\":\"0\",\"variables\":[\"\"],"
                + "\"ranges\":{\"x\":[0]},\"samples\":0,\"tolerance\":-1}",
            "{}"),
        c(
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            "{\"lhs\":\"x\",\"rhs\":\"0\",\"variables\":[\"x\",\"x\"],"
                + "\"ranges\":{\"x\":[0,1]},\"samples\":2,\"tolerance\":0}",
            "{}"),
        c(
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            "{\"lhs\":\"x\",\"rhs\":\"0\",\"variables\":[\"x\"],"
                + "\"ranges\":{\"x\":1},\"samples\":2,\"tolerance\":\"bad\"}",
            "{}"),
        c(ComputationMethod.MODULAR_EXHAUSTIVE, "{}", "{}"),
        c(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x\",\"modulus\":1,\"relation\":\"lt\",\"variables\":\"x\"}",
            "{\"x\":[]}"),
        c(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x\",\"modulus\":2,\"variables\":[\"x\"]}",
            "{\"x\":1}"),
        c(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x\",\"modulus\":2,\"variables\":[\"x\"]}",
            "{\"x\":{\"values\":[]}}"),
        c(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x\",\"modulus\":2,\"variables\":[\"x\"]}",
            "{\"x\":{\"values\":1}}"),
        c(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x\",\"modulus\":2,\"variables\":[\"x\"]}",
            "{\"x\":{\"min\":0.5,\"max\":1}}"),
        c(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x\",\"modulus\":2,\"variables\":[\"x\"]}",
            "{\"x\":{\"min\":0,\"max\":1}}"),
        c(ComputationMethod.BOUNDED_INTEGER_SEARCH, "{}", "{}"),
        c(ComputationMethod.BOUNDED_INTEGER_SEARCH, "{\"target\":1}", "{\"x\":1}"),
        c(
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            "{\"target\":{\"lhs\":\"x\",\"relation\":\"bad\"},\"constraints\":1}",
            "{\"x\":{}}"),
        c(
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            "{\"target\":{\"lhs\":\"x\"},\"constraints\":[null,{\"lhs\":\"y\"}]}",
            "{\"x\":{\"min\":0.5,\"max\":1}}"),
        c(
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            "{\"target\":{\"lhs\":\"x\"},\"constraints\":[]}",
            "{\"x\":{\"min\":2,\"max\":1}}"),
        c(
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            "{\"target\":{\"lhs\":\"x\"},\"constraints\":[]}",
            "{\"x\":{\"min\":1,\"max\":2}}"),
        c(ComputationMethod.GRAPH_CERTIFICATE, "{}", "{}"),
        c(
            ComputationMethod.GRAPH_CERTIFICATE,
            "{\"graph\":[],\"property\":\"bad\",\"certificate\":[]}",
            "{}"),
        c(
            ComputationMethod.GRAPH_CERTIFICATE,
            "{\"graph\":{\"nodes\":1,\"edges\":1},\"property\":\"connected\","
                + "\"certificate\":{}}",
            "{}"),
        c(
            ComputationMethod.GRAPH_CERTIFICATE,
            "{\"graph\":{\"nodes\":[\"a\",\"a\"],\"edges\":[[\"a\"]]},"
                + "\"property\":\"connected\",\"certificate\":{}}",
            "{}"),
        c(
            ComputationMethod.GRAPH_CERTIFICATE,
            "{\"graph\":{\"nodes\":[\"a\"],\"edges\":[[\"a\",\"a\"]]},"
                + "\"property\":\"connected\",\"certificate\":{}}",
            "{}"),
        c(ComputationMethod.RECURRENCE_CHECK, "{}", "{}"),
        c(
            ComputationMethod.RECURRENCE_CHECK,
            "{\"initial_values\":1,\"coefficients\":1,\"end_n\":true}",
            "{}"),
        c(
            ComputationMethod.RECURRENCE_CHECK,
            "{\"initial_values\":[],\"coefficients\":[],\"end_n\":2,\"start_n\":\"x\"}",
            "{}"),
        c(
            ComputationMethod.RECURRENCE_CHECK,
            "{\"initial_values\":[1],\"coefficients\":[1,1],\"end_n\":0,\"start_n\":1}",
            "{}"),
        c(
            ComputationMethod.RECURRENCE_CHECK,
            "{\"initial_values\":[1,1],\"coefficients\":[1,1],\"end_n\":3}",
            "{}"),
        c(ComputationMethod.BOUNDED_GREEDY_SEQUENCE, "{}", "{}"),
        c(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":1,\"length\":\"x\",\"rule\":\"bad\","
                + "\"claimed_values\":1,\"candidate_min\":\"x\","
                + "\"strictly_increasing\":\"yes\"}",
            "{}"),
        c(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":1,\"length\":1,\"rule\":\"coprime_to_all\","
                + "\"claimed_values\":[],\"candidate_min\":0,\"candidate_max\":\"bad\"}",
            "{}"),
        c(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[],\"length\":0,\"rule\":\"avoid_forbidden_differences\","
                + "\"forbidden_differences\":[]}",
            "{}"),
        c(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[1,2],\"length\":1,\"rule\":\"avoid_forbidden_differences\","
                + "\"forbidden_differences\":null,\"claimed_values\":null}",
            "{}"),
        c(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[1,2],\"length\":3,\"rule\":\"coprime_to_all\","
                + "\"claimed_values\":[1,2,3],\"candidate_min\":4,\"candidate_max\":3,"
                + "\"strictly_increasing\":true}",
            "{}"),
        c(ComputationMethod.CANDIDATE_PERIOD_CHECK, "{}", "{}"),
        c(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":1,\"candidate_period\":\"x\",\"start_index\":\"x\"}",
            "{}"),
        c(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[],\"candidate_period\":0,\"start_index\":-1}",
            "{}"),
        c(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2],\"candidate_period\":2}",
            "{}"),
        c(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1],\"candidate_period\":1,\"start_index\":0}",
            "{}"),
        c(ComputationMethod.EXACT_GEOMETRY, "{}", "{}"),
        c(ComputationMethod.EXACT_GEOMETRY, "{\"points\":[],\"assertion\":[]}", "{}"),
        c(
            ComputationMethod.EXACT_GEOMETRY,
            "{\"points\":{\"A\":[0]},\"assertion\":{\"kind\":\"bad\"}}",
            "{}"),
        c(
            ComputationMethod.EXACT_GEOMETRY,
            "{\"points\":{\"A\":[0,0]},\"assertion\":{\"kind\":\"collinear\","
                + "\"points\":[\"A\"]}}",
            "{}"),
        c(
            ComputationMethod.EXACT_GEOMETRY,
            "{\"points\":{\"A\":[0,0],\"B\":[1,0],\"C\":[2,0]},"
                + "\"assertion\":{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"X\"]}}",
            "{}"),
        c(
            ComputationMethod.EXACT_GEOMETRY,
            "{\"points\":{\"A\":[0,0],\"B\":[1,0],\"C\":[2,0]},"
                + "\"assertion\":{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"C\"]}}",
            "{}"),
        c(ComputationMethod.REAL_INEQUALITY, "{}", "{}"),
        c(
            ComputationMethod.REAL_INEQUALITY,
            "{\"lhs\":\"x\",\"rhs\":1,\"relation\":\"bad\",\"variables\":1,"
                + "\"max_runtime_ms\":true}",
            "{\"x\":1}"),
        c(
            ComputationMethod.REAL_INEQUALITY,
            "{\"lhs\":\"x\",\"relation\":\"ge\",\"variables\":[\"x\"],"
                + "\"max_runtime_ms\":0}",
            "{\"x\":{\"unknown\":1,\"min\":0.5,\"max\":\"1/2\","
                + "\"positive\":\"yes\"}}"),
        c(
            ComputationMethod.REAL_INEQUALITY,
            "{\"lhs\":\"x\",\"relation\":\"ge\",\"variables\":[\"x\"],"
                + "\"max_runtime_ms\":1}",
            "{\"x\":{\"min\":\"0\",\"max\":\"1/2\",\"positive\":true}}"),
        c(ComputationMethod.NUMBER_THEORY_CHECK, "{}", "{}"),
        c(
            ComputationMethod.NUMBER_THEORY_CHECK,
            "{\"operation\":\"multiplicative_order\",\"a\":true,\"n\":0.5}",
            "{}"),
        c(
            ComputationMethod.NUMBER_THEORY_CHECK,
            "{\"operation\":\"crt\",\"residues\":1,\"moduli\":[]}",
            "{}"),
        c(
            ComputationMethod.NUMBER_THEORY_CHECK,
            "{\"operation\":\"crt\",\"residues\":[1],\"moduli\":[2,3]}",
            "{}"),
        c(
            ComputationMethod.NUMBER_THEORY_CHECK,
            "{\"operation\":\"p_adic_valuation\",\"p\":2,\"expression\":\"\","
                + "\"assignment\":1}",
            "{}"),
        c(
            ComputationMethod.NUMBER_THEORY_CHECK,
            "{\"operation\":\"p_adic_valuation\",\"p\":2,\"expression\":\"x\","
                + "\"assignment\":{\"x\":0.5}}",
            "{}"),
        c(ComputationMethod.NUMBER_THEORY_CHECK, "{\"operation\":\"primitive_root\"}", "{}"),
        c(ComputationMethod.NUMBER_THEORY_CHECK, "{\"operation\":\"is_prime\",\"n\":2}", "{}"),
        c(ComputationMethod.NUMBER_THEORY_CHECK, "{\"operation\":\"factorization\",\"n\":2}", "{}"),
        c(ComputationMethod.SANDBOXED_PYTHON, "{}", "{}"),
        c(ComputationMethod.SANDBOXED_PYTHON, "{\"input\":[]}", "{}"),
        c(ComputationMethod.SANDBOXED_PYTHON, "{\"input\":{}}", "{}"),
        c(ComputationMethod.LEAN_CHECK, "{}", "{}"),
        c(ComputationMethod.LEAN_CHECK, "{\"source\":\"theorem t : True := by trivial\"}", "{}"));
  }

  private static Arguments c(
      ComputationMethod method, String arguments, String domains) {
    return Arguments.of(method, arguments, domains);
  }
}
