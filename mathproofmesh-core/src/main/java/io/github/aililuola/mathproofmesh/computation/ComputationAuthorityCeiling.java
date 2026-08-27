package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;

public enum ComputationAuthorityCeiling {
  AUDIT_ONLY,
  BOUNDED_OBSERVATION,
  EXACT_COUNTEREXAMPLE,
  FINITE_DOMAIN_CERTIFICATE,
  FORMAL_CERTIFICATE;

  public boolean permits(ComputationVerifiedAuthority authority) {
    if (authority == ComputationVerifiedAuthority.AUDIT_ONLY) {
      return true;
    }
    return switch (this) {
      case AUDIT_ONLY -> false;
      case BOUNDED_OBSERVATION ->
          authority == ComputationVerifiedAuthority.BOUNDED_OBSERVATION;
      case EXACT_COUNTEREXAMPLE ->
          authority == ComputationVerifiedAuthority.BOUNDED_OBSERVATION
              || authority == ComputationVerifiedAuthority.EXACT_COUNTEREXAMPLE;
      case FINITE_DOMAIN_CERTIFICATE ->
          authority == ComputationVerifiedAuthority.BOUNDED_OBSERVATION
              || authority == ComputationVerifiedAuthority.EXACT_COUNTEREXAMPLE
              || authority == ComputationVerifiedAuthority.FINITE_DOMAIN_CERTIFICATE;
      case FORMAL_CERTIFICATE -> true;
    };
  }
}
