package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PythonDifferentialParityTest {
  @Test
  void javaGeneratedValuesMatchReadOnlyPythonOracle() throws IOException, InterruptedException {
    Path root = Path.of(System.getProperty("mathproofmesh.projectRoot"));
    Path python =
        System.getProperty("os.name").startsWith("Windows")
            ? root.resolve(".venv-baseline/Scripts/python.exe")
            : root.resolve(".venv-baseline/bin/python");
    Path oracle = root.resolve("scripts/phase02-python-hash-oracle.py");
    ProcessBuilder processBuilder =
        new ProcessBuilder(python.toString(), oracle.toString())
            .directory(root.toFile())
            .redirectErrorStream(true);
    processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
    Process process = processBuilder.start();

    List<TestValue> values = javaGeneratedValues();
    try (BufferedWriter input =
            new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader output =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      for (TestValue value : values) {
        ObjectNode request = ContractObjectMapper.parseTree("{}").deepCopy();
        request.put("mode", value.mode());
        request.set("input", value.input());
        input.write(ContractObjectMapper.write(request));
        input.newLine();
        input.flush();
        JsonNode response = ContractObjectMapper.parseTree(output.readLine());
        assertEquals(
            CanonicalJson.canonicalize(value.input()),
            response.get("canonical_json").textValue());
        assertEquals(
            value.expectedHash(), response.get("stable_hash").textValue(), value.name());
      }
    }
    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
  }

  private static List<TestValue> javaGeneratedValues() {
    List<TestValue> values = new ArrayList<>();
    JsonNode unicode = ContractObjectMapper.parseTree("\"\\ud83d\\ude80\"");
    values.add(
        new TestValue(
            "raw-supplementary",
            "raw_utf8_string",
            unicode,
            CanonicalJson.stableHash(unicode.textValue())));
    JsonNode nested =
        ContractObjectMapper.parseTree(
            """
            {"\ud83d\ude80":"supplementary","\uffff":"bmp","a":{"z":-1,"items":[3,2,1]}}
            """);
    values.add(
        new TestValue(
            "unicode-key-order",
            "canonical_json",
            nested,
            CanonicalJson.stableHash(nested)));
    JsonNode smallFloat = ContractObjectMapper.parseTree("1e-7");
    values.add(
        new TestValue(
            "small-float",
            "canonical_json",
            smallFloat,
            CanonicalJson.stableHash(smallFloat)));
    ClaimCard claim =
        ContractObjectMapper.read(
            """
            {
              "claim_id":"claim_java_to_python",
              "statement":"A",
              "assumptions":["H"],
              "conclusion":"B",
              "dependencies":["claim-base"]
            }
            """,
            ClaimCard.class);
    JsonNode claimTree = claim.toJsonTree();
    values.add(
        new TestValue(
            "claim-card",
            "canonical_json",
            claimTree,
            CanonicalJson.stableHash(claim)));
    NoveltySignature novelty =
        ContractObjectMapper.read(
            """
            {
              "representation_tags":["Graph","Graph","\ud83d\ude80"],
              "mechanism_tags":["Bridge"],
              "key_transformations":["Dualize"],
              "proof_principles":["Induction"],
              "targeted_obligation_ids":["obl-b","obl-a"]
            }
            """,
            NoveltySignature.class);
    values.add(
        new TestValue(
            "novelty-signature",
            "canonical_json",
            novelty.normalizedPayload(),
            novelty.normalizedHash()));
    MechanismChainSignature mechanism =
        MechanismChainSignature.fromNoveltySignature(novelty);
    values.add(
        new TestValue(
            "mechanism-chain",
            "canonical_json",
            mechanism.normalizedPayload(),
            mechanism.chainHash()));
    ExperimentSpec spec =
        ContractObjectMapper.read(
            """
            {
              "purpose":"discover_pattern",
              "target_claim":"Find a bounded pattern.",
              "reasoning_basis":"The finite domain is exact.",
              "why_computation_is_needed":"No symbolic pattern is known.",
              "decision_if_confirmed":"Create a proof obligation.",
              "decision_if_refuted":"Discard the route.",
              "noncomputational_alternative":"Continue symbolic work.",
              "method":"bounded_greedy_sequence",
              "domains":{"n":{"min":0,"max":8}},
              "arguments":{"candidate_min":2},
              "broad_search":true,
              "runtime_fingerprint":{"engine":"java","seed":99}
            }
            """,
            ExperimentSpec.class);
    values.add(
        new TestValue(
            "experiment-request",
            "canonical_json",
            spec.normalizedPayload(),
            spec.requestHash()));
    values.add(
        new TestValue(
            "experiment-execution",
            "canonical_json",
            spec.normalizedExecutionPayload(),
            spec.executionHash()));
    ExperimentProgram program =
        ContractObjectMapper.read(
            """
            {"experiment_id":"experiment-a","source":"print('exact')"}
            """,
            ExperimentProgram.class);
    values.add(
        new TestValue(
            "experiment-program-source",
            "raw_utf8_string",
            ContractObjectMapper.parseTree(ContractObjectMapper.write(program.source())),
            program.codeHash()));
    ExperimentResult result =
        ContractObjectMapper.read(
            """
            {
              "experiment_id":"experiment-a",
              "request_hash":"request-a",
              "target_claim":"The bounded cases agree.",
              "method":"bounded_integer_search",
              "outcome":"not_refuted",
              "evidence_strength":"bounded_evidence",
              "scope":{"min":0,"max":8},
              "exact_arithmetic":true,
              "cases_checked":9,
              "tool_name":"java-handler",
              "tool_version":"1",
              "verification_notes":["All cases were exact."]
            }
            """,
            ExperimentResult.class);
    values.add(
        new TestValue(
            "experiment-result",
            "canonical_json",
            ContractHashes.experimentResultPayload(
                result.requestHash(),
                result.targetClaim(),
                result.method(),
                result.outcome(),
                result.evidenceStrength(),
                result.scope(),
                result.counterexample(),
                result.certificate(),
                result.exactArithmetic(),
                result.casesChecked(),
                result.toolName(),
                result.toolVersion(),
                result.programHash(),
                result.independentlyVerified(),
                result.verificationNotes(),
                result.error()),
            result.resultHash()));
    return List.copyOf(values);
  }

  private record TestValue(String name, String mode, JsonNode input, String expectedHash) {}
}
