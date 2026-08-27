package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase17ComputationContractHardeningTest {

  @Test
  void everyTypedContractRejectsMalformedAndOutOfScopeArguments() {
    invalid(ComputationMethod.SYMPY_SIMPLIFY, "{}");
    invalid(ComputationMethod.SYMPY_SIMPLIFY, "{\"expression\":7}");
    valid(ComputationMethod.SYMPY_SIMPLIFY, "{\"expression\":\"x+x\"}");
    invalid(ComputationMethod.SYMPY_EQUIVALENT, "{\"lhs\":\"x\"}");
    invalid(ComputationMethod.SYMPY_EQUIVALENT, "{\"lhs\":\" \",\"rhs\":7}");
    valid(ComputationMethod.SYMPY_EQUIVALENT, "{\"lhs\":\"x+x\",\"rhs\":\"2*x\"}");
    invalid(ComputationMethod.POLYNOMIAL_FACTOR, "{}");
    valid(ComputationMethod.POLYNOMIAL_FACTOR, "{\"expression\":\"x^2-1\"}");

    invalid(
        ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        "{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"bad\",\"variables\":\"x\","
            + "\"ranges\":[],\"samples\":0,\"tolerance\":true}");
    invalid(
        ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        "{\"lhs\":\"x\",\"rhs\":\"0\",\"variables\":[\"x\",\"x\"],"
            + "\"ranges\":{\"x\":[0]},\"samples\":1.5,\"tolerance\":-1}");
    invalid(
        ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        "{\"lhs\":\"x\",\"rhs\":\"0\",\"variables\":[\"\"],"
            + "\"ranges\":{\"x\":[0,1,2]}}");
    valid(
        ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        "{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"ge\",\"variables\":[\"x\"],"
            + "\"ranges\":{\"x\":[-1,1]},\"samples\":10,\"tolerance\":0}");

    invalid(ComputationMethod.MODULAR_EXHAUSTIVE, "{}", "{}");
    invalid(
        ComputationMethod.MODULAR_EXHAUSTIVE,
        "{\"lhs\":\"x\",\"modulus\":1,\"relation\":\"lt\",\"variables\":[\"x\",\"x\"]}",
        "{\"x\":[]}");
    invalid(
        ComputationMethod.MODULAR_EXHAUSTIVE,
        "{\"lhs\":\"x\",\"modulus\":\"2\"}",
        "{\"x\":\"bad\"}");
    invalid(
        ComputationMethod.MODULAR_EXHAUSTIVE,
        "{\"lhs\":\"x\",\"modulus\":2}",
        "{\"x\":{\"values\":[]}}");
    invalid(
        ComputationMethod.MODULAR_EXHAUSTIVE,
        "{\"lhs\":\"x\",\"modulus\":2}",
        "{\"x\":{\"values\":\"bad\"}}");
    invalid(
        ComputationMethod.MODULAR_EXHAUSTIVE,
        "{\"lhs\":\"x\",\"modulus\":2}",
        "{\"x\":{\"min\":\"bad\",\"max\":3}}");
    valid(
        ComputationMethod.MODULAR_EXHAUSTIVE,
        "{\"lhs\":\"x\",\"rhs\":\"0\",\"modulus\":5,\"relation\":\"eq\","
            + "\"variables\":[\"x\"]}",
        "{\"x\":{\"min\":0,\"max\":4}}");

    invalid(ComputationMethod.BOUNDED_INTEGER_SEARCH, "{}", "{}");
    invalid(
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        "{\"target\":\"bad\",\"constraints\":\"bad\"}",
        "{\"x\":\"bad\"}");
    invalid(
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        "{\"target\":{},\"constraints\":[null]}",
        "{\"x\":{\"min\":0}}");
    invalid(
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        "{\"target\":{\"lhs\":\"x\",\"relation\":\"bad\"},"
            + "\"constraints\":[{\"lhs\":\"x\",\"relation\":\"bad\"}]}",
        "{\"x\":{\"min\":\"bad\",\"max\":2}}");
    invalid(
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        "{\"target\":{\"lhs\":\"x\"}}",
        "{\"x\":{\"min\":3,\"max\":2}}");
    valid(
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        "{\"target\":{\"lhs\":\"x\",\"relation\":\"ge\"},"
            + "\"constraints\":[{\"lhs\":\"x-2\",\"relation\":\"le\"}]}",
        "{\"x\":{\"min\":0,\"max\":4}}");

    invalid(ComputationMethod.GRAPH_CERTIFICATE, "{}");
    invalid(
        ComputationMethod.GRAPH_CERTIFICATE,
        "{\"graph\":{\"nodes\":\"bad\",\"edges\":[[\"a\"]]},"
            + "\"property\":\"bad\",\"certificate\":[]}");
    invalid(
        ComputationMethod.GRAPH_CERTIFICATE,
        "{\"graph\":{\"nodes\":[\"a\",\"a\"],\"edges\":\"bad\"},"
            + "\"property\":\"connected\",\"certificate\":{}}");
    valid(
        ComputationMethod.GRAPH_CERTIFICATE,
        "{\"graph\":{\"nodes\":[\"a\",\"b\"],\"edges\":[[\"a\",\"b\"]]},"
            + "\"property\":\"connected\",\"certificate\":{}}");

    invalid(ComputationMethod.RECURRENCE_CHECK, "{}");
    invalid(
        ComputationMethod.RECURRENCE_CHECK,
        "{\"initial_values\":[],\"coefficients\":[],\"end_n\":\"bad\","
            + "\"start_n\":\"bad\"}");
    invalid(
        ComputationMethod.RECURRENCE_CHECK,
        "{\"initial_values\":[1],\"coefficients\":[1,1],\"start_n\":3,\"end_n\":2}");
    valid(
        ComputationMethod.RECURRENCE_CHECK,
        "{\"initial_values\":[0,1],\"coefficients\":[1,1],\"start_n\":0,\"end_n\":8}");

    invalid(ComputationMethod.BOUNDED_GREEDY_SEQUENCE, "{}");
    invalid(
        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
        "{\"initial_values\":[1.5],\"length\":\"bad\",\"rule\":\"bad\","
            + "\"claimed_values\":\"bad\",\"candidate_min\":\"bad\","
            + "\"candidate_max\":\"bad\",\"strictly_increasing\":\"yes\"}");
    invalid(
        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
        "{\"initial_values\":[1,2],\"length\":1,"
            + "\"rule\":\"avoid_forbidden_differences\"}");
    invalid(
        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
        "{\"initial_values\":[1],\"length\":3,\"rule\":\"coprime_to_all\","
            + "\"candidate_min\":5,\"candidate_max\":4}");
    valid(
        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
        "{\"initial_values\":[1],\"length\":3,\"rule\":\"coprime_to_all\","
            + "\"claimed_values\":[1,2,3]}");
    valid(
        discovery(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[1],\"length\":3,\"rule\":\"coprime_to_all\"}",
            "{}"));

    invalid(ComputationMethod.CANDIDATE_PERIOD_CHECK, "{}");
    invalid(
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        "{\"values\":[],\"candidate_period\":\"bad\",\"start_index\":\"bad\"}");
    invalid(
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        "{\"values\":[1,2],\"candidate_period\":0,\"start_index\":-1}");
    invalid(
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        "{\"values\":[1,2,1],\"candidate_period\":2,\"start_index\":1}");
    valid(
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        "{\"values\":[1,2,1,2],\"candidate_period\":2,\"start_index\":0}");

    invalid(ComputationMethod.EXACT_GEOMETRY, "{}");
    invalid(
        ComputationMethod.EXACT_GEOMETRY,
        "{\"points\":{\"a\":[0]},\"assertion\":[]}");
    invalid(
        ComputationMethod.EXACT_GEOMETRY,
        "{\"points\":{\"a\":[0,0]},\"assertion\":{\"kind\":\"bad\",\"points\":[]}}");
    invalid(
        ComputationMethod.EXACT_GEOMETRY,
        "{\"points\":{\"a\":[0,0],\"b\":[1,0]},"
            + "\"assertion\":{\"kind\":\"collinear\",\"points\":[\"a\",\"b\"]}}");
    invalid(
        ComputationMethod.EXACT_GEOMETRY,
        "{\"points\":{\"a\":[0,0],\"b\":[1,0],\"c\":[2,0]},"
            + "\"assertion\":{\"kind\":\"collinear\",\"points\":[\"a\",\"b\",\"d\"]}}");
    valid(
        ComputationMethod.EXACT_GEOMETRY,
        "{\"points\":{\"a\":[0,0],\"b\":[1,0],\"c\":[2,0]},"
            + "\"assertion\":{\"kind\":\"collinear\",\"points\":[\"a\",\"b\",\"c\"]}}");

    invalid(ComputationMethod.REAL_INEQUALITY, "{}", "{}");
    invalid(
        ComputationMethod.REAL_INEQUALITY,
        "{\"lhs\":\"x\",\"rhs\":7,\"relation\":\"bad\",\"variables\":\"x\","
            + "\"max_runtime_ms\":0}",
        "{\"x\":\"bad\"}");
    invalid(
        ComputationMethod.REAL_INEQUALITY,
        "{\"lhs\":\"x\",\"max_runtime_ms\":true}",
        "{\"x\":{\"unknown\":1,\"min\":1.5,\"max\":true,\"positive\":\"yes\","
            + "\"nonnegative\":0,\"nonzero\":null,\"min_exclusive\":\"no\","
            + "\"max_exclusive\":1}}");
    valid(
        ComputationMethod.REAL_INEQUALITY,
        "{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"ge\",\"variables\":[\"x\"],"
            + "\"max_runtime_ms\":50}",
        "{\"x\":{\"min\":\"0\",\"max\":\"2\",\"nonnegative\":true}}");

    invalid(ComputationMethod.NUMBER_THEORY_CHECK, "{}");
    invalid(
        ComputationMethod.NUMBER_THEORY_CHECK,
        "{\"operation\":\"multiplicative_order\",\"a\":\"bad\",\"n\":1.5}");
    invalid(
        ComputationMethod.NUMBER_THEORY_CHECK,
        "{\"operation\":\"crt\",\"residues\":[],\"moduli\":\"bad\"}");
    invalid(
        ComputationMethod.NUMBER_THEORY_CHECK,
        "{\"operation\":\"crt\",\"residues\":[1,2],\"moduli\":[3]}");
    invalid(
        ComputationMethod.NUMBER_THEORY_CHECK,
        "{\"operation\":\"p_adic_valuation\",\"p\":\"bad\",\"expression\":\"\","
            + "\"assignment\":[]}");
    invalid(
        ComputationMethod.NUMBER_THEORY_CHECK,
        "{\"operation\":\"p_adic_valuation\",\"p\":2,\"expression\":\"x\","
            + "\"assignment\":{\"x\":1.5}}");
    valid(
        ComputationMethod.NUMBER_THEORY_CHECK,
        "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":5}");
    valid(
        ComputationMethod.NUMBER_THEORY_CHECK,
        "{\"operation\":\"crt\",\"residues\":[1,2],\"moduli\":[3,5]}");
    valid(
        ComputationMethod.NUMBER_THEORY_CHECK,
        "{\"operation\":\"p_adic_valuation\",\"p\":2,\"expression\":\"x\","
            + "\"assignment\":{\"x\":8}}");
    for (String operation : new String[] {"primitive_root", "is_prime", "factorization"}) {
      valid(
          ComputationMethod.NUMBER_THEORY_CHECK,
          "{\"operation\":\"" + operation + "\",\"n\":7}");
    }

    invalid(ComputationMethod.SANDBOXED_PYTHON, "{\"input\":[]}");
    valid(ComputationMethod.SANDBOXED_PYTHON, "{\"input\":{}}");
    invalid(ComputationMethod.LEAN_CHECK, "{\"source\":\"\"}");
    valid(ComputationMethod.LEAN_CHECK, "{\"source\":\"example : True := by trivial\"}");

    invalid(ComputationMethod.SYMPY_SIMPLIFY, "{\"expression\":\"x\",\"extra\":1}");
    invalid(
        ComputationFixtures.spec(
            ComputationMethod.SYMPY_SIMPLIFY,
            "{\"expression\":\"x\"}",
            "{\"x\":{\"min\":0,\"max\":1}}"));
  }

  @Test
  void catalogAndExploratoryNormalizationCoverAllSelectionModes() {
    assertThat(ContractsFunctions.experimentToolCatalog(null))
        .hasSize(io.github.aililuola.mathproofmesh.contract.ComputationMethod.values().length);
    assertThat(ContractsFunctions.experimentToolCatalog(Set.of()))
        .hasSize(io.github.aililuola.mathproofmesh.contract.ComputationMethod.values().length);
    assertThat(
            ContractsFunctions.experimentToolCatalog(
                Set.of(ComputationMethod.BOUNDED_GREEDY_SEQUENCE.value())))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.path("allowed_rules").size()).isEqualTo(4);
              assertThat(entry.path("unknown_alias_policy").asText()).isNotBlank();
            });

    ExperimentSpec nonGreedy =
        ComputationFixtures.spec(ComputationMethod.SYMPY_SIMPLIFY, "{\"expression\":\"x\"}");
    assertThat(ContractsFunctions.normalizeExploratoryContract(nonGreedy).changed()).isFalse();

    ExperimentSpec claimed =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[1],\"length\":2,\"rule\":\"coprime_to_all\","
                + "\"claimed_values\":[1,2]}");
    assertThat(ContractsFunctions.normalizeExploratoryContract(claimed).changed()).isFalse();

    ExperimentSpec discovery =
        discovery(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[1],\"length\":2,\"rule\":\"coprime_to_all\"}",
            "{}");
    assertThat(ContractsFunctions.normalizeExploratoryContract(discovery).changed()).isFalse();
  }

  private static ExperimentSpec discovery(
      ComputationMethod method, String arguments, String domains) {
    return ComputationFixtures.spec(
        method, arguments, domains, ComputationPurpose.DISCOVER_PATTERN, true, 1_000);
  }

  private static void valid(
      ComputationMethod method, String arguments) {
    valid(ComputationFixtures.spec(method, arguments));
  }

  private static void valid(
      ComputationMethod method, String arguments, String domains) {
    valid(ComputationFixtures.spec(method, arguments, domains));
  }

  private static void valid(ExperimentSpec spec) {
    assertThat(ContractsFunctions.validateExperimentContract(spec)).isEmpty();
  }

  private static void invalid(
      ComputationMethod method, String arguments) {
    invalid(ComputationFixtures.spec(method, arguments));
  }

  private static void invalid(
      ComputationMethod method, String arguments, String domains) {
    invalid(ComputationFixtures.spec(method, arguments, domains));
  }

  private static void invalid(ExperimentSpec spec) {
    assertThat(ContractsFunctions.validateExperimentContract(spec)).isNotEmpty();
  }
}
