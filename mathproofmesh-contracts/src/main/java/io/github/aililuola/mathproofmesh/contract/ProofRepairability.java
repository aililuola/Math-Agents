package io.github.aililuola.mathproofmesh.contract;

public enum ProofRepairability {
  LOCAL_PATCH,
  VERIFIED_DEPENDENCY_PATCH,
  NEW_SUBCLAIM_REQUIRED,
  NONLOCAL_REWRITE_REQUIRED,
  NOT_REPAIRABLE,
  UNKNOWN
}
