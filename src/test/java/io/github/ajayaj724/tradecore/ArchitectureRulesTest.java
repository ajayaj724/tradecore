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

    @ArchTest
    static final ArchRule noSystemClock = noClasses()
            .should()
            .callMethod(java.time.Instant.class, "now")
            .orShould()
            .callMethod(java.time.LocalDateTime.class, "now")
            .orShould()
            .callMethod(java.time.LocalDate.class, "now")
            .orShould()
            .callMethod(java.time.LocalTime.class, "now")
            .orShould()
            .callMethod(java.time.OffsetDateTime.class, "now")
            .orShould()
            .callMethod(java.time.ZonedDateTime.class, "now")
            .orShould()
            .callMethod(System.class, "currentTimeMillis")
            .because("time comes from an injected java.time.Clock; zero-arg now() breaks"
                    + " deterministic tests — now(Clock) overloads are allowed (CLAUDE.md)");
}
