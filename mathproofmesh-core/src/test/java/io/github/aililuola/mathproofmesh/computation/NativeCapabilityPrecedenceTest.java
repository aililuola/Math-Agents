package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeCapabilityPrecedenceTest {
  @Test
  void sandboxCannotBypassAnAvailableNativeLinearAlgebraCapability() {
    var original = ComputationFixtures.spec(ComputationMethod.SANDBOXED_PYTHON, "{\"input\":{}}");
    ObjectNode tree = (ObjectNode) ContractObjectMapper.toTree(original);
    tree.put("typed_tool_gap", "exact linear algebra is unavailable");
    tree.remove("execution_hash");
    tree.remove("request_hash");
    ExperimentSpec spec = ContractObjectMapper.read(tree, ExperimentSpec.class);
    var compiler = new ComputationRequestCompiler("native-precedence", ComputationIssue010TestSupport.registry());
    assertThatThrownBy(
            () -> compiler.compile(spec, new ExperimentProgram(null, null, List.of(), spec.experimentId(),
                    ComputationFixtures.object("{}"), ComputationFixtures.object("{}"),
                    "def run(data):\n return data\n"),
                ComputationExecutionContext.legacy("path-computation")))
        .hasMessageContaining("NATIVE_CAPABILITY_PRECEDENCE");
  }
}
