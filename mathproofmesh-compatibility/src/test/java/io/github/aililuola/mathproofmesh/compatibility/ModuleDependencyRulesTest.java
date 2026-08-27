package io.github.aililuola.mathproofmesh.compatibility;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "io.github.aililuola.mathproofmesh",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ModuleDependencyRulesTest {
    @ArchTest
    static final ArchRule CONTRACTS_ARE_FRAMEWORK_FREE = noClasses()
            .that().resideInAPackage("..contract..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.github.aililuola.mathproofmesh.core..",
                    "io.github.aililuola.mathproofmesh.server..",
                    "io.github.aililuola.mathproofmesh.desktop..",
                    "io.github.aililuola.mathproofmesh.compatibility..",
                    "org.springframework..",
                    "io.temporal..",
                    "org.openjfx..",
                    "javafx..",
                    "java.sql..",
                    "javax.sql.."
            );

    @ArchTest
    static final ArchRule CORE_IS_FRAMEWORK_FREE = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.github.aililuola.mathproofmesh.server..",
                    "io.github.aililuola.mathproofmesh.desktop..",
                    "io.github.aililuola.mathproofmesh.compatibility..",
                    "org.springframework..",
                    "io.temporal..",
                    "org.openjfx..",
                    "javafx..",
                    "java.sql..",
                    "javax.sql.."
            );

    @ArchTest
    static final ArchRule SERVER_DOES_NOT_DEPEND_ON_OUTER_MODULES = noClasses()
            .that().resideInAPackage("..server..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.github.aililuola.mathproofmesh.desktop..",
                    "io.github.aililuola.mathproofmesh.compatibility.."
            );

    @ArchTest
    static final ArchRule PRODUCTION_MODULES_DO_NOT_DEPEND_ON_COMPATIBILITY = noClasses()
            .that().resideInAnyPackage(
                    "..contract..",
                    "..core..",
                    "..server..",
                    "..desktop.."
            )
            .should().dependOnClassesThat().resideInAPackage(
                    "io.github.aililuola.mathproofmesh.compatibility..");
}
