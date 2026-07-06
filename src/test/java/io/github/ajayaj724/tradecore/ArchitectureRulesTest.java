package io.github.ajayaj724.tradecore;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.github.ajayaj724.tradecore")
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should()
            .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .because("constructor injection only (CLAUDE.md)");

    @ArchTest
    static final ArchRule noLombok = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAPackage("lombok..")
            .because("no Lombok (CLAUDE.md)");
}
