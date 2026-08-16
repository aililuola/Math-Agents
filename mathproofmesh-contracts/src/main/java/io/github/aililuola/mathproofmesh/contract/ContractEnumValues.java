package io.github.aililuola.mathproofmesh.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Derives JSON-schema enum values from the enum's canonical JSON representation. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface ContractEnumValues {
  Class<? extends Enum<?>> value();
}
