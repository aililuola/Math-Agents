package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Single source of truth for capability contracts, producers, verifiers, and prompts. */
public final class ComputationCapabilityRegistry {
  public static final String CAPABILITY_VERSION = "1";
  public static final String PRODUCER_VERSION = "java-native/1";
  public static final String VERIFIER_VERSION = "java-independent-verifier/1";

  private final Map<ComputationMethod, RegisteredComputationCapability> capabilities;

  private ComputationCapabilityRegistry(
      Map<ComputationMethod, RegisteredComputationCapability> capabilities) {
    this.capabilities = Map.copyOf(capabilities);
    if (!this.capabilities.keySet().equals(EnumSet.allOf(ComputationMethod.class))) {
      EnumSet<ComputationMethod> missing = EnumSet.allOf(ComputationMethod.class);
      missing.removeAll(this.capabilities.keySet());
      throw new IllegalStateException("unregistered computation methods: " + missing);
    }
  }

  public static ComputationCapabilityRegistry standard(
      ExternalComputationHandler externalHandler) {
    Map<ComputationMethod, RegisteredComputationCapability> result =
        new EnumMap<>(ComputationMethod.class);
    addExternal(result, ComputationMethod.SYMPY_SIMPLIFY, Set.of("expression"), false, externalHandler);
    addExternal(result, ComputationMethod.SYMPY_EQUIVALENT, Set.of("lhs", "rhs"), false, externalHandler);
    addExternal(result, ComputationMethod.POLYNOMIAL_FACTOR, Set.of("expression"), false, externalHandler);
    addNative(
        result,
        ComputationMethod.MODULAR_EXHAUSTIVE,
        Set.of(
            "lhs",
            "rhs",
            "modulus",
            "relation",
            "variables",
            "finite_reduction",
            "reduction_justification"),
        true,
        ComputationDeterminism.DETERMINISTIC_EXACT,
        ComputationAuthorityCeiling.FINITE_DOMAIN_CERTIFICATE,
        noProgram(ModularFunctions::run));
    addNative(
        result,
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        Set.of("target", "constraints"),
        true,
        ComputationDeterminism.DETERMINISTIC_BOUNDED,
        ComputationAuthorityCeiling.FINITE_DOMAIN_CERTIFICATE,
        noProgram(IntegerSearchFunctions::run));
    addNative(
        result,
        ComputationMethod.GRAPH_CERTIFICATE,
        Set.of("graph", "property", "certificate"),
        false,
        ComputationDeterminism.DETERMINISTIC_EXACT,
        ComputationAuthorityCeiling.FINITE_DOMAIN_CERTIFICATE,
        noProgram(GraphFunctions::run));
    addNative(
        result,
        ComputationMethod.RECURRENCE_CHECK,
        Set.of(
            "initial_values",
            "coefficients",
            "start_n",
            "end_n",
            "inhomogeneous",
            "claimed_expression"),
        false,
        ComputationDeterminism.DETERMINISTIC_BOUNDED,
        ComputationAuthorityCeiling.FINITE_DOMAIN_CERTIFICATE,
        noProgram(RecurrenceFunctions::run));
    addNative(
        result,
        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
        Set.of(
            "initial_values",
            "length",
            "candidate_min",
            "candidate_max",
            "strictly_increasing",
            "rule",
            "forbidden_differences",
            "claimed_values"),
        false,
        ComputationDeterminism.DETERMINISTIC_BOUNDED,
        ComputationAuthorityCeiling.EXACT_COUNTEREXAMPLE,
        noProgram(SequenceFunctions::runBoundedGreedySequence));
    addNative(
        result,
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        Set.of("values", "candidate_period", "start_index"),
        false,
        ComputationDeterminism.DETERMINISTIC_BOUNDED,
        ComputationAuthorityCeiling.FINITE_DOMAIN_CERTIFICATE,
        noProgram(SequenceFunctions::runCandidatePeriodCheck));
    addNative(
        result,
        ComputationMethod.EXACT_GEOMETRY,
        Set.of("points", "assertion"),
        false,
        ComputationDeterminism.DETERMINISTIC_EXACT,
        ComputationAuthorityCeiling.FINITE_DOMAIN_CERTIFICATE,
        noProgram(GeometryFunctions::run));
    addSpecialExternal(
        result,
        ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        Set.of("lhs", "rhs", "relation", "variables", "ranges", "samples", "tolerance"),
        ComputationBackendKind.EXTERNAL_TYPED,
        ComputationDeterminism.SEEDED_SAMPLED,
        ComputationAuthorityCeiling.EXACT_COUNTEREXAMPLE,
        externalHandler,
        false);
    addSpecialExternal(
        result,
        ComputationMethod.REAL_INEQUALITY,
        Set.of("lhs", "rhs", "relation", "variables", "max_runtime_ms"),
        ComputationBackendKind.EXTERNAL_TYPED,
        ComputationDeterminism.DETERMINISTIC_EXACT,
        ComputationAuthorityCeiling.EXACT_COUNTEREXAMPLE,
        externalHandler,
        true);
    addNative(
        result,
        ComputationMethod.NUMBER_THEORY_CHECK,
        Set.of(
            "operation",
            "a",
            "n",
            "p",
            "residues",
            "moduli",
            "expression",
            "assignment",
            "claimed"),
        false,
        ComputationDeterminism.DETERMINISTIC_EXACT,
        ComputationAuthorityCeiling.FINITE_DOMAIN_CERTIFICATE,
        noProgram(NumberTheoryFunctions::run));
    addNative(
        result,
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        Set.of("operation", "matrix", "rhs", "vector"),
        false,
        ComputationDeterminism.DETERMINISTIC_EXACT,
        ComputationAuthorityCeiling.FINITE_DOMAIN_CERTIFICATE,
        noProgram(ExactLinearAlgebraFunctions::run));
    addNative(
        result,
        ComputationMethod.FINITE_SET_MAP_CHECK,
        Set.of("operation", "domain", "codomain", "mapping", "target"),
        false,
        ComputationDeterminism.DETERMINISTIC_EXACT,
        ComputationAuthorityCeiling.FINITE_DOMAIN_CERTIFICATE,
        noProgram(FiniteSetMapFunctions::run));
    addNative(
        result,
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        Set.of("operation", "vertices", "edges", "candidate"),
        false,
        ComputationDeterminism.DETERMINISTIC_BOUNDED,
        ComputationAuthorityCeiling.FINITE_DOMAIN_CERTIFICATE,
        noProgram(HypergraphTransversalFunctions::run));
    addSpecialExternal(
        result,
        ComputationMethod.SANDBOXED_PYTHON,
        Set.of("input"),
        ComputationBackendKind.SANDBOXED_PYTHON,
        ComputationDeterminism.SEEDED_SAMPLED,
        ComputationAuthorityCeiling.BOUNDED_OBSERVATION,
        externalHandler);
    addSpecialExternal(
        result,
        ComputationMethod.LEAN_CHECK,
        Set.of("source"),
        ComputationBackendKind.FORMAL_KERNEL,
        ComputationDeterminism.EXTERNAL_FORMAL,
        ComputationAuthorityCeiling.FORMAL_CERTIFICATE,
        externalHandler);
    return new ComputationCapabilityRegistry(result);
  }

  public static ComputationCapabilityRegistry javaOnly() {
    return standard(null);
  }

  public RegisteredComputationCapability capability(ComputationMethod method) {
    RegisteredComputationCapability result = capabilities.get(method);
    if (result == null) {
      throw new IllegalArgumentException("unknown computation capability: " + method);
    }
    return result;
  }

  public Optional<RegisteredComputationCapability> find(ComputationMethod method) {
    return Optional.ofNullable(capabilities.get(method));
  }

  public Set<ComputationMethod> methods() {
    return Set.copyOf(capabilities.keySet());
  }

  public boolean available(ComputationMethod method) {
    return capability(method).available();
  }

  public ComputationCapabilitySnapshot snapshot() {
    return new ComputationCapabilitySnapshot(
        capabilities.values().stream().map(RegisteredComputationCapability::descriptor).toList(),
        null);
  }

  public List<ObjectNode> promptCatalog(Set<String> allowedTools) {
    List<ObjectNode> catalog = new ArrayList<>();
    capabilities.values().stream()
        .sorted(java.util.Comparator.comparing(value -> value.descriptor().method().value()))
        .forEach(
            value -> {
              ComputationMethod method = value.descriptor().method();
              if (allowedTools != null
                  && !allowedTools.isEmpty()
                  && !allowedTools.contains(method.value())) {
                return;
              }
              ObjectNode item = ComputationJson.object();
              item.put("method", method.value());
              item.put("capability_id", value.descriptor().capabilityId());
              item.put("capability_version", value.descriptor().capabilityVersion());
              item.put("backend", value.descriptor().backendKind().name().toLowerCase(java.util.Locale.ROOT));
              item.put("authority_ceiling", value.descriptor().authorityCeiling().name().toLowerCase(java.util.Locale.ROOT));
              item.put("domains", value.acceptsDomains() ? "typed" : "unused");
              ArrayNode arguments = item.putArray("allowed_arguments");
              new TreeSet<>(value.allowedArguments()).forEach(arguments::add);
              if (method == ComputationMethod.SANDBOXED_PYTHON) {
                item.putArray("required_arguments").add("input");
                item
                    .putArray("constraints")
                    .add("Last resort only; typed_tool_gap must identify an unavailable typed capability.")
                    .add("arguments.input must be the complete JSON object supplied to run(data).")
                    .add("Do not place sandbox inputs beside input; such fields are rejected.")
                    .add("Native capabilities take precedence over sandbox execution.")
                    .add("Sandbox output cannot grant formal or finite-domain authority.");
              }
              catalog.add(item);
            });
    return List.copyOf(catalog);
  }

  private static void addNative(
      Map<ComputationMethod, RegisteredComputationCapability> result,
      ComputationMethod method,
      Set<String> arguments,
      boolean acceptsDomains,
      ComputationDeterminism determinism,
      ComputationAuthorityCeiling ceiling,
      ComputationHandler handler) {
    String producerId = "native-producer/" + method.value();
    ComputationCapabilityDescriptor descriptor =
        descriptor(
            method,
            arguments,
            acceptsDomains,
            ComputationBackendKind.NATIVE_JAVA,
            determinism,
            ceiling,
            producerId,
            PRODUCER_VERSION,
            "native-verifier/" + method.value(),
            VERIFIER_VERSION);
    ComputationProducer producer =
        request ->
            new ProducedComputation(
                handler.execute(request.spec(), request.program()), producerId, PRODUCER_VERSION);
    result.put(
        method,
        new RegisteredComputationCapability(
            descriptor,
            arguments,
            acceptsDomains,
            true,
            producer,
            new IndependentComputationCertificateVerifier()));
  }

  private static void addExternal(
      Map<ComputationMethod, RegisteredComputationCapability> result,
      ComputationMethod method,
      Set<String> arguments,
      boolean acceptsDomains,
      ExternalComputationHandler externalHandler) {
    addSpecialExternal(
        result,
        method,
        arguments,
        ComputationBackendKind.EXTERNAL_TYPED,
        method == ComputationMethod.NUMERIC_COUNTEREXAMPLE
            ? ComputationDeterminism.SEEDED_SAMPLED
            : ComputationDeterminism.DETERMINISTIC_EXACT,
        ComputationAuthorityCeiling.BOUNDED_OBSERVATION,
        externalHandler,
        acceptsDomains);
  }

  private static void addSpecialExternal(
      Map<ComputationMethod, RegisteredComputationCapability> result,
      ComputationMethod method,
      Set<String> arguments,
      ComputationBackendKind backend,
      ComputationDeterminism determinism,
      ComputationAuthorityCeiling ceiling,
      ExternalComputationHandler externalHandler) {
    addSpecialExternal(
        result, method, arguments, backend, determinism, ceiling, externalHandler, false);
  }

  private static void addSpecialExternal(
      Map<ComputationMethod, RegisteredComputationCapability> result,
      ComputationMethod method,
      Set<String> arguments,
      ComputationBackendKind backend,
      ComputationDeterminism determinism,
      ComputationAuthorityCeiling ceiling,
      ExternalComputationHandler externalHandler,
      boolean acceptsDomains) {
    boolean available = externalHandler != null && externalHandler.supports(method);
    String producerId = "external-producer/" + method.value();
    String producerVersion =
        available ? externalHandler.toolIdentity(method) : "backend-unavailable/1";
    ComputationCapabilityDescriptor descriptor =
        descriptor(
            method,
            arguments,
            acceptsDomains,
            backend,
            determinism,
            ceiling,
            producerId,
            producerVersion,
            "independent-verifier/" + method.value(),
            VERIFIER_VERSION);
    ComputationProducer producer =
        request -> {
          HandlerEvidence evidence =
              available
                  ? externalHandler.execute(request.spec(), request.program())
                  : HandlerEvidence.inconclusive(
                      "BACKEND_UNAVAILABLE: no configured backend for " + method.value(),
                      ComputationJson.object().put("method", method.value()));
          return new ProducedComputation(evidence, producerId, producerVersion);
        };
    result.put(
        method,
        new RegisteredComputationCapability(
            descriptor,
            arguments,
            acceptsDomains,
            available,
            producer,
            new IndependentComputationCertificateVerifier()));
  }

  private static ComputationCapabilityDescriptor descriptor(
      ComputationMethod method,
      Set<String> arguments,
      boolean acceptsDomains,
      ComputationBackendKind backend,
      ComputationDeterminism determinism,
      ComputationAuthorityCeiling ceiling,
      String producerId,
      String producerVersion,
      String verifierId,
      String verifierVersion) {
    Map<String, Object> inputSchema =
        Map.of(
            "method", method.value(),
            "arguments", new TreeSet<>(arguments),
            "accepts_domains", acceptsDomains);
    Map<String, Object> outputSchema =
        Map.of(
            "result", "typed",
            "certificate", "required",
            "verification_receipt", "required");
    return new ComputationCapabilityDescriptor(
        method,
        "mathproofmesh/" + method.value(),
        CAPABILITY_VERSION,
        backend,
        determinism,
        ceiling,
        true,
        backend != ComputationBackendKind.SANDBOXED_PYTHON,
        CanonicalJson.stableHash(inputSchema),
        CanonicalJson.stableHash(outputSchema),
        ComputationResourceEnvelope.boundedDefault(),
        producerId,
        producerVersion,
        verifierId,
        verifierVersion);
  }

  private static ComputationHandler noProgram(
      java.util.function.Function<io.github.aililuola.mathproofmesh.contract.ExperimentSpec, HandlerEvidence>
          function) {
    return (spec, program) -> function.apply(spec);
  }
}
