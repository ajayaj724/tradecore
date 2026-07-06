package io.github.ajayaj724.tradecore;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(TradecoreApplication.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    void writesArchitectureDocs() {
        new Documenter(modules).writeDocumentation();
    }
}
