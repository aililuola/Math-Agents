package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ComputationCertificateEnvelope;
import io.github.aililuola.mathproofmesh.contract.ComputationCertificateType;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import org.junit.jupiter.api.Test;

class ComputationCertificateContractsTest {
  @Test
  void certificateHashIsContentAddressedAndTamperEvident() {
    var certificate = new ComputationCertificateEnvelope(
        "r".repeat(64), "e".repeat(64), "cap", "1", "s".repeat(64), "d".repeat(64),
        "x".repeat(64), ComputationCertificateType.FINITE_EXHAUSTIVE_COVERAGE, 2, 2,
        (ObjectNode) ContractObjectMapper.parseTree("{\"ok\":true}"), "c".repeat(64), "producer", "1", null);
    assertThat(certificate.certificateHash()).hasSize(64);
    ObjectNode tree = (ObjectNode) ContractObjectMapper.toTree(certificate);
    tree.put("certificate_hash", "0".repeat(64));
    assertThatThrownBy(() -> ContractObjectMapper.read(tree, ComputationCertificateEnvelope.class))
        .isInstanceOf(ContractValidationException.class);
  }
}
