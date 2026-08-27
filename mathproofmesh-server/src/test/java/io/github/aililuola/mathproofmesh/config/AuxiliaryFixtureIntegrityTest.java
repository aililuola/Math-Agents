package io.github.aililuola.mathproofmesh.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AuxiliaryFixtureIntegrityTest {
  private static final Path ROOT =
      Path.of(System.getProperty("mathproofmesh.projectRoot"));

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void baselineCopyRetainsAuthoritativeSha256(String file, String expected)
      throws IOException, NoSuchAlgorithmException {
    byte[] content =
        Files.readAllBytes(ROOT.resolve("migration/baseline/auxiliary").resolve(file));
    String actual =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));

    assertEquals(expected, actual);
  }

  static Stream<Arguments> fixtures() {
    return Stream.of(
        Arguments.of(
            ".env.example",
            "66f1068ce43817a63fb453a2b4e12ef4de210d3e2732e801733b481edc1c0971"),
        Arguments.of(
            "config.deepseek-v4-pro.proof-control-active.yaml",
            "6173be9ac59aec5de64d3c4d2605283b5524a68447a44a6c2ebf284255aa9192"),
        Arguments.of(
            "config.deepseek-v4-pro.proof-control-shadow.yaml",
            "83ad1623d0fa7bb657e411186d86188f9bc4f33b5984bcda7026f600a1bbd58a"),
        Arguments.of(
            "config.deepseek-v4-pro.smoke.yaml",
            "5f6ce9b8e74ad733c50d4d8f21496ba893c987b6c56d446f2630a25faa1feacb"),
        Arguments.of(
            "config.deepseek-v4-pro.topology-active.yaml",
            "351ab270aa1fb35f05bb5344564bdcc69cb3e5017953d5515b5878dc471df7f4"),
        Arguments.of(
            "config.deepseek-v4-pro.yaml",
            "c5fe586cc9b1cea4e76ce0aa47fbf74039443daf67d254774edd1ce116a1604a"),
        Arguments.of(
            "config.example.yaml",
            "9733bcbf4f8d87c9bbceb4b5f22f54bc8384c80a2eda922a934fc7dab4945a68"),
        Arguments.of(
            "benchmarks/topology/README.md",
            "6be46038332e75e28f93e7e0af62ec9c333d35c852987ccdc0f0fdc2d0531530"),
        Arguments.of(
            "benchmarks/topology/run_mock_benchmark.py",
            "928e08cbdfb6f5e6f9645e0fcc79699bf4bd3d8f91b537f5772347ce4db9e94d"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/PROOF_OBLIGATION_GRAPH.md",
            "0ac4b062b87b334db458c9460962000324084a19a8886898657085bf1e448b7b"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/TYPED_MEMORY.md",
            "fb7601020dec99f0317cd510c32b4509299a01adfa9b91291da367fbf7757026"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/DEEPSEEK_V4_PRO.md",
            "48e5773893ff7540efe826a85af1fc86364a03f2df231461db4f2110ac543e52"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/PROMPT_PROTOCOL.md",
            "22d22fdcf5cf7e16f2c2e4325c4c68cdea484d2f0aaef89afbc41e0e82448921"),
        Arguments.of(
            "benchmarks/reasoning_first_computation.py",
            "941d372eed641ff4c681ff3121d482a4532af800d208f1b9d7d318623f5154f3"),
        Arguments.of(
            "../scripts/directed_computation_smoke.py",
            "7030c1b35df780872a5598dc19231307776ec5f132fc5a948ae35d64dcf2cd6c"),
        Arguments.of(
            "../scripts/verify_sandbox.py",
            "c739ff316f392e67c033e97f339eaae26d075b258261a4e005c88430364509bb"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/COMPUTATION_POLICY.md",
            "ca63c15615938d408b83c0f5a1e588f8f72b2b661794c52d13d93a56a4193bd1"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/AGENT_CAPABILITY_PROFILE.md",
            "4a77762aae88e8992bf1d7b0a9277bf381f49a5331627a3b2781cd3b0236543b"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/VALIDATION_ESCALATION.md",
            "f1d615a2c2b7fd400734f213c2fabcb08e98ad4877d8ab91166636e4d3d770f5"),
        Arguments.of(
            "benchmarks/proof_control/README.md",
            "4b21001c294379fab3350df2e55361d69eb181d9fc5cbc8a2224203f6b462a83"),
        Arguments.of(
            "benchmarks/proof_control/run_mock_benchmark.py",
            "e08b64c05acb6a0a8e1678ba3315027cbce20887b640cd2485ac81a60ba72be6"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/GOAL_PLAN_FAILURE_UTILITY_CONTROL.md",
            "3fb72e601d1f936cb693b8ae49583de1dc381aebed53c0a549303ba23673a846"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/NEAR_MISS_AND_REALIZER_REPAIR.md",
            "bdecdc352fb3db43c36d9435a6095bd479533535a96221efe68923d9833c5599"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/PROOF_CONTROL_MIGRATION_0.8.md",
            "d051aa9435241874f265aca71a076d03a3cc6744eccd651405cc8fc7cc4a4825"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/PROOF_SCOPE_AND_INFERENCE_RISKS.md",
            "0799c94ad14c98a2e16b102709ebc47029cb50fc24319566de46b6a4d0d30909"),
        Arguments.of(
            "../../../mathproofmesh-compatibility/src/test/resources/benchmarks/analogy_library.jsonl",
            "01a4d209de6ea708370ea9e1530a789f13161e6f6c388b033b9f858e4e06bad0"),
        Arguments.of(
            "../../../docs/legacy/python-baseline/INSPIRATION_ENGINE.md",
            "a971b7877d82eba1ee1cf33a096577fd8873e68679a3cb96ae544bf52a78fe1f"));
  }
}
