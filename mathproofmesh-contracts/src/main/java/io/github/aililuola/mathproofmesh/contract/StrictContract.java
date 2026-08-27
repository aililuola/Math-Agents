package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.databind.JsonNode;

public interface StrictContract {
  default JsonNode toJsonTree() {
    return ContractObjectMapper.toTree(this);
  }

  default String canonicalJson() {
    return CanonicalJson.canonicalize(this);
  }

  default String stableHash() {
    return CanonicalJson.stableHash(this);
  }
}
