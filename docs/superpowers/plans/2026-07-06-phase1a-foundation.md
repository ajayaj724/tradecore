# tradecore Phase 1A — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A running Spring Modulith 2.1 / Boot 4.1 skeleton on Java 25 with the full quality gate, docker-compose platform (Postgres 18, Keycloak, OTel→Prometheus/Tempo/Loki/Grafana), OIDC security, RFC 9457 errors, and the Modulith event registry proven by an integration test.

**Architecture:** Single Maven module, modular monolith. This plan builds the *chassis*: build gate, infra, security, observability, event registry. Domain modules (orders/risk/execution + matching engine) land in Plan 1B on top of this.

**Tech Stack:** Java 25 · Spring Boot 4.1.x · Spring Modulith 2.1.x · Spring Data JDBC · PostgreSQL 18 · Flyway · Keycloak · OpenTelemetry · Testcontainers · Maven.

## Global Constraints (from approved spec)

- Base package: `io.github.ajayaj724.tradecore`. Money (later plans): `BIGINT` minor units only.
- Versions: latest stable GA, verified against `repo1.maven.org` maven-metadata.xml at execution time (Task 1). Never override Boot-BOM-managed versions. No milestones/RCs.
- No Lombok. Constructor injection only. No unauthenticated endpoints except health/readiness and OpenAPI docs.
- Errors: RFC 9457 Problem Details, always (`application/problem+json`).
- All schema via Flyway; roll-forward only.
- Before EVERY commit: `mvn spotless:apply && mvn verify` must be green (tradecore-quality-gate skill; from Task 2 onward `pom.xml` exists, so the gate is live).
- Conventional commits, `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` trailer.

---

### Task 1: Verify current versions (dependency currency)

**Files:**
- Create: `docs/superpowers/plans/versions-2026-07.md` (scratch record, committed for traceability)

**Interfaces:**
- Produces: the version numbers used in Tasks 2–3 property blocks. Baselines below are fallbacks; the verified numbers WIN.

- [ ] **Step 1: Fetch latest GA versions from repo1 (ground truth)**

```bash
for gav in \
  org/springframework/boot/spring-boot-starter-parent \
  org/springframework/modulith/spring-modulith-bom \
  com/diffplug/spotless/spotless-maven-plugin \
  com/google/errorprone/error_prone_core \
  com/uber/nullaway/nullaway \
  org/apache/maven/plugins/maven-checkstyle-plugin \
  com/puppycrawl/tools/checkstyle/checkstyle \
  org/jacoco/jacoco-maven-plugin \
  com/tngtech/archunit/archunit-junit5 ; do
  echo "== $gav" ; curl -s "https://repo1.maven.org/maven2/$gav/maven-metadata.xml" | grep -E '<release>'
done
```

Expected: one `<release>X.Y.Z</release>` line per artifact. Reject any `-M`/`-RC` value (take newest GA below it from the full `<version>` list).

- [ ] **Step 2: Record results**

Write the table (artifact → verified version → baseline it replaces) to `docs/superpowers/plans/versions-2026-07.md`. Baselines (used if a fetch fails, and to sanity-check for typos): Boot parent `4.1.0`, Modulith BOM `2.1.0`, Spotless `2.46.1`, Error Prone `2.42.0`, NullAway `0.12.10`, maven-checkstyle-plugin `3.6.0`, Checkstyle `10.26.1`, JaCoCo `0.8.13`, ArchUnit `1.4.1`. If JaCoCo's release notes don't list Java 25 bytecode support, keep the JaCoCo rule at WARN not FAIL and file a TODO issue — do not skip the plugin.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/versions-2026-07.md
git commit -m "docs: verified dependency versions for Phase 1A"
```

---

### Task 2: Maven skeleton, app class, first integration test

**Files:**
- Create: `pom.xml`, `.gitignore`, `.mvn/jvm.config`
- Create: `src/main/java/io/github/ajayaj724/tradecore/TradecoreApplication.java`
- Create: `src/main/resources/application.yaml`
- Test: `src/test/java/io/github/ajayaj724/tradecore/TestcontainersConfig.java`, `src/test/java/io/github/ajayaj724/tradecore/TradecoreApplicationIT.java`

**Interfaces:**
- Produces: `TradecoreApplication` (Boot entry point); `TestcontainersConfig` providing `@ServiceConnection PostgreSQLContainer` reused by every later IT; failsafe runs `*IT.java`, surefire runs `*Test.java`.

- [ ] **Step 1: Write the failing integration test**

`src/test/java/io/github/ajayaj724/tradecore/TestcontainersConfig.java`:

```java
package io.github.ajayaj724.tradecore;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>("postgres:18");
  }
}
```

`src/test/java/io/github/ajayaj724/tradecore/TradecoreApplicationIT.java`:

```java
package io.github.ajayaj724.tradecore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfig.class)
class TradecoreApplicationIT {

  @Test
  void contextLoads(ApplicationContext context) {
    assertThat(context.getId()).isEqualTo("tradecore");
  }
}
```

- [ ] **Step 2: Create `pom.xml`** (versions from Task 1; structure below is complete except the gate plugins, which Task 3 adds)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version> <!-- Task 1 verified value -->
    <relativePath/>
  </parent>

  <groupId>io.github.ajayaj724</groupId>
  <artifactId>tradecore</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>tradecore</name>
  <description>Enterprise brokerage OMS — modular monolith showcase</description>

  <properties>
    <java.version>25</java.version>
    <spring-modulith.version>2.1.0</spring-modulith.version> <!-- Task 1 verified -->
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-bom</artifactId>
        <version>${spring-modulith.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jdbc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.modulith</groupId><artifactId>spring-modulith-starter-core</artifactId></dependency>
    <dependency><groupId>org.springframework.modulith</groupId><artifactId>spring-modulith-starter-jdbc</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>

    <!-- test -->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.modulith</groupId><artifactId>spring-modulith-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <executions>
          <execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

`.gitignore`:

```
target/
*.iml
.idea/
.vscode/
.DS_Store
```

`.mvn/jvm.config` (required by Error Prone on JDK 16+; added now so Task 3 doesn't touch two files):

```
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
```

- [ ] **Step 3: Create the application class and base config**

`src/main/java/io/github/ajayaj724/tradecore/TradecoreApplication.java`:

```java
package io.github.ajayaj724.tradecore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "tradecore")
@SpringBootApplication
public class TradecoreApplication {

  public static void main(String[] args) {
    SpringApplication.run(TradecoreApplication.class, args);
  }
}
```

`src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: tradecore
  main:
    application-id: tradecore
  threads:
    virtual:
      enabled: true
  flyway:
    enabled: true
  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: false   # Flyway owns all schema (Task 6)
      republish-outstanding-events-on-restart: true
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

Note: if `spring.main.application-id` is not a real property in Boot 4.1 (check with the executor's IDE/`spring-configuration-metadata`), instead make the test assert `context.getEnvironment().getProperty("spring.application.name")` equals `"tradecore"` — do NOT invent properties.

- [ ] **Step 4: Run the test — verify it fails first, then passes**

Run: `mvn verify` (Docker must be running).
First run before Step 3 files existed: compile FAILURE (no application class) — that was the RED.
After Step 3: expect `TradecoreApplicationIT` PASS, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply 2>/dev/null; mvn verify && git add -A && git commit -m "feat: Boot 4.1 + Modulith 2.1 skeleton on Java 25 with Testcontainers IT"
```

---

### Task 3: Quality gate plugins — Spotless, Error Prone + NullAway, Checkstyle, JaCoCo

**Files:**
- Modify: `pom.xml` (add plugins inside `<build><plugins>`)
- Create: `config/checkstyle/checkstyle.xml`

**Interfaces:**
- Produces: `mvn verify` = the full machine gate referenced by the tradecore-quality-gate skill. All later tasks inherit it.

- [ ] **Step 1: Add plugin blocks to `pom.xml`** (versions from Task 1)

```xml
      <plugin>
        <groupId>com.diffplug.spotless</groupId>
        <artifactId>spotless-maven-plugin</artifactId>
        <version>2.46.1</version> <!-- Task 1 verified -->
        <configuration>
          <java>
            <palantirJavaFormat/>
            <removeUnusedImports/>
          </java>
        </configuration>
        <executions>
          <execution><goals><goal>check</goal></goals><phase>validate</phase></execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <release>25</release>
          <compilerArgs>
            <arg>-XDcompilePolicy=simple</arg>
            <arg>--should-stop=ifError=FLOW</arg>
            <arg>-Xplugin:ErrorProne -XepExcludedPaths:.*/target/.* \
                 -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages=io.github.ajayaj724.tradecore</arg>
          </compilerArgs>
          <annotationProcessorPaths>
            <path><groupId>com.google.errorprone</groupId><artifactId>error_prone_core</artifactId><version>2.42.0</version></path>
            <path><groupId>com.uber.nullaway</groupId><artifactId>nullaway</artifactId><version>0.12.10</version></path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-checkstyle-plugin</artifactId>
        <version>3.6.0</version> <!-- Task 1 verified -->
        <configuration>
          <configLocation>config/checkstyle/checkstyle.xml</configLocation>
          <failOnViolation>true</failOnViolation>
          <includeTestSourceDirectory>true</includeTestSourceDirectory>
        </configuration>
        <dependencies>
          <dependency><groupId>com.puppycrawl.tools</groupId><artifactId>checkstyle</artifactId><version>10.26.1</version></dependency>
        </dependencies>
        <executions>
          <execution><goals><goal>check</goal></goals><phase>validate</phase></execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
        <version>0.8.13</version> <!-- Task 1 verified; see Task 1 note re Java 25 -->
        <executions>
          <execution><goals><goal>prepare-agent</goal></goals></execution>
          <execution>
            <id>check</id>
            <goals><goal>check</goal></goals>
            <configuration>
              <rules>
                <rule>
                  <element>BUNDLE</element>
                  <limits>
                    <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.80</minimum></limit>
                  </limits>
                </rule>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
```

- [ ] **Step 2: Create `config/checkstyle/checkstyle.xml`**

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
  "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
  <property name="severity" value="error"/>
  <module name="LineLength">
    <property name="max" value="120"/>
  </module>
  <module name="TreeWalker">
    <module name="MethodLength">
      <property name="max" value="40"/>
      <property name="countEmpty" value="false"/>
    </module>
    <module name="CyclomaticComplexity">
      <property name="max" value="10"/>
    </module>
    <module name="UnusedImports"/>
    <module name="OneTopLevelClass"/>
    <module name="OuterTypeFilename"/>
    <module name="MissingSwitchDefault"/>
    <module name="EqualsHashCode"/>
  </module>
</module>
```

- [ ] **Step 3: Prove the gate bites (RED), then go green**

Temporarily add an unused import to `TradecoreApplication.java`, run `mvn verify` → expect Checkstyle/Spotless FAILURE. Remove it. Run `mvn spotless:apply && mvn verify` → expect BUILD SUCCESS. (JaCoCo: with only the app class, coverage of the bundle may sit below 80% — if `jacoco:check` fails here, add `<excludes><exclude>**/TradecoreApplication.class</exclude></excludes>` to the check configuration; the entry point is glue, not logic.)

- [ ] **Step 4: Commit**

```bash
mvn spotless:apply && mvn verify && git add -A && git commit -m "build: quality gate — Spotless, Error Prone+NullAway, Checkstyle, JaCoCo 80%"
```

---

### Task 4: Modulith verification + ArchUnit framework rules

**Files:**
- Test: `src/test/java/io/github/ajayaj724/tradecore/ModularityTests.java`
- Test: `src/test/java/io/github/ajayaj724/tradecore/ArchitectureRulesTest.java`
- Modify: `pom.xml` (add ArchUnit test dependency)

**Interfaces:**
- Produces: `ModularityTests` — every future module must pass `verify()`; `docs/architecture/` C4+PlantUML output regenerated on every build.

- [ ] **Step 1: Add ArchUnit dependency to `pom.xml`**

```xml
    <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><version>1.4.1</version><scope>test</scope></dependency> <!-- Task 1 verified -->
```

- [ ] **Step 2: Write the tests**

`ModularityTests.java`:

```java
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
```

`ArchitectureRulesTest.java`:

```java
package io.github.ajayaj724.tradecore;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "io.github.ajayaj724.tradecore")
class ArchitectureRulesTest {

  @ArchTest
  static final ArchRule noFieldInjection =
      noFields().should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
          .because("constructor injection only (CLAUDE.md)");

  @ArchTest
  static final ArchRule noLombok =
      noClasses().should().dependOnClassesThat().resideInAPackage("lombok..")
          .because("no Lombok (CLAUDE.md)");
}
```

- [ ] **Step 3: Run, expect green; commit**

Run: `mvn spotless:apply && mvn verify` → PASS (rules trivially hold on the skeleton; they exist to catch Plan 1B+ violations).

```bash
git add -A && git commit -m "test: Modulith verification + ArchUnit framework rules"
```

---

### Task 5: Compose platform — Postgres 18 + Keycloak with tradecore realm

**Files:**
- Create: `compose.yaml`
- Create: `infra/keycloak/tradecore-realm.json`

**Interfaces:**
- Produces: Postgres at `localhost:5432` (db/user/pass `tradecore`), Keycloak at `http://localhost:8081`, realm `tradecore`, client `tradecore-api`, users `trader1`/`ops1`/`admin1` (password `demo`, realm roles `TRADER`/`OPS`/`ADMIN`). Task 7 and the README depend on these exact values.

- [ ] **Step 1: Create `compose.yaml`**

```yaml
name: tradecore
services:
  postgres:
    image: postgres:18
    environment:
      POSTGRES_DB: tradecore
      POSTGRES_USER: tradecore
      POSTGRES_PASSWORD: tradecore
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U tradecore"]
      interval: 5s
      timeout: 3s
      retries: 10

  keycloak:
    image: quay.io/keycloak/keycloak:26.3   # verify latest GA tag at execution time
    command: ["start-dev", "--import-realm", "--http-port=8080"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
    volumes:
      - ./infra/keycloak/tradecore-realm.json:/opt/keycloak/data/import/tradecore-realm.json:ro
    ports: ["8081:8080"]
```

- [ ] **Step 2: Create `infra/keycloak/tradecore-realm.json`**

```json
{
  "realm": "tradecore",
  "enabled": true,
  "roles": {
    "realm": [
      {"name": "TRADER"},
      {"name": "OPS"},
      {"name": "ADMIN"}
    ]
  },
  "clients": [
    {
      "clientId": "tradecore-api",
      "enabled": true,
      "publicClient": true,
      "directAccessGrantsEnabled": true,
      "standardFlowEnabled": false,
      "protocol": "openid-connect"
    }
  ],
  "users": [
    {
      "username": "trader1", "enabled": true, "email": "trader1@tradecore.local", "emailVerified": true,
      "credentials": [{"type": "password", "value": "demo", "temporary": false}],
      "realmRoles": ["TRADER"]
    },
    {
      "username": "ops1", "enabled": true, "email": "ops1@tradecore.local", "emailVerified": true,
      "credentials": [{"type": "password", "value": "demo", "temporary": false}],
      "realmRoles": ["OPS"]
    },
    {
      "username": "admin1", "enabled": true, "email": "admin1@tradecore.local", "emailVerified": true,
      "credentials": [{"type": "password", "value": "demo", "temporary": false}],
      "realmRoles": ["ADMIN"]
    }
  ]
}
```

- [ ] **Step 3: Verify the platform boots and issues tokens**

```bash
docker compose up -d postgres keycloak
docker compose ps            # both healthy/running
sleep 20
curl -s -X POST http://localhost:8081/realms/tradecore/protocol/openid-connect/token \
  -d grant_type=password -d client_id=tradecore-api -d username=trader1 -d password=demo \
  | python3 -c "import json,sys; t=json.load(sys.stdin); print('TOKEN OK' if 'access_token' in t else t)"
```

Expected: `TOKEN OK`. (Demo passwords in a committed realm file are intentional — local demo only; note this in the file header comment in README, Task 8.)

- [ ] **Step 4: Commit**

```bash
mvn spotless:apply && mvn verify && git add -A && git commit -m "build: compose platform — Postgres 18 + Keycloak with tradecore realm"
```

---

### Task 6: Flyway baseline + Modulith event registry proven by IT

**Files:**
- Create: `src/main/resources/db/migration/V1__event_publication.sql`
- Test: `src/test/java/io/github/ajayaj724/tradecore/events/EventRegistryIT.java`
- Test: `src/test/java/io/github/ajayaj724/tradecore/events/EventFixtures.java` (fixtures stay in test scope — nothing event-related lands in main until Plan 1B)

**Interfaces:**
- Produces: `event_publication` table owned by Flyway; proof that `@ApplicationModuleListener` events persist and complete. Plan 1B's cross-module events rely on this exact mechanism.

- [ ] **Step 1: Write the failing IT**

`src/test/java/io/github/ajayaj724/tradecore/events/EventFixtures.java`:

```java
package io.github.ajayaj724.tradecore.events;

import java.util.concurrent.CountDownLatch;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

public class EventFixtures {

  public record PingEvent(String value) {}

  @Component
  public static class PingPublisher {
    private final ApplicationEventPublisher events;

    PingPublisher(ApplicationEventPublisher events) {
      this.events = events;
    }

    @Transactional
    public void ping(String value) {
      events.publishEvent(new PingEvent(value));
    }
  }

  @Component
  public static class PingListener {
    public static final CountDownLatch RECEIVED = new CountDownLatch(1);

    @ApplicationModuleListener
    void on(PingEvent event) {
      RECEIVED.countDown();
    }
  }
}
```

`EventRegistryIT.java`:

```java
package io.github.ajayaj724.tradecore.events;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = {io.github.ajayaj724.tradecore.TradecoreApplication.class,
    EventFixtures.PingPublisher.class, EventFixtures.PingListener.class})
@Import(TestcontainersConfig.class)
class EventRegistryIT {

  @Autowired EventFixtures.PingPublisher publisher;
  @Autowired JdbcClient jdbc;

  @Test
  void persistsAndCompletesEventPublications() throws Exception {
    publisher.ping("hello");

    assertThat(EventFixtures.PingListener.RECEIVED.await(10, TimeUnit.SECONDS)).isTrue();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      Long completed = jdbc.sql(
          "select count(*) from event_publication where completion_date is not null")
          .query(Long.class).single();
      assertThat(completed).isGreaterThanOrEqualTo(1L);
    });
  }
}
```

Add `awaitility` (test scope, Boot-managed) to `pom.xml`:

```xml
    <dependency><groupId>org.awaitility</groupId><artifactId>awaitility</artifactId><scope>test</scope></dependency>
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn verify -Dit.test=EventRegistryIT`
Expected: FAIL — Flyway error `relation "event_publication" does not exist` (schema init disabled, no migration yet).

- [ ] **Step 3: Create `V1__event_publication.sql`**

Extract the canonical DDL from the Modulith jar to keep it exactly in sync with 2.1.0:

```bash
unzip -p ~/.m2/repository/org/springframework/modulith/spring-modulith-events-jdbc/2.1.0/spring-modulith-events-jdbc-2.1.0.jar \
  schema-postgresql.sql
```

Copy that output verbatim into `src/main/resources/db/migration/V1__event_publication.sql`. Fallback if extraction fails (verify column names against the jar before using):

```sql
CREATE TABLE IF NOT EXISTS event_publication (
  id               UUID NOT NULL,
  listener_id      TEXT NOT NULL,
  event_type       TEXT NOT NULL,
  serialized_event TEXT NOT NULL,
  publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date  TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
  ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
  ON event_publication (completion_date);
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn verify -Dit.test=EventRegistryIT` → PASS.

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply && mvn verify && git add -A && git commit -m "feat: Flyway-owned event_publication schema + registry integration test"
```

---

### Task 7: Security — OAuth2 resource server, role mapping, RFC 9457 everywhere

**Files:**
- Create: `src/main/java/io/github/ajayaj724/tradecore/config/SecurityConfig.java`
- Create: `src/main/java/io/github/ajayaj724/tradecore/config/ProblemDetailsAuthHandlers.java`
- Create: `src/main/java/io/github/ajayaj724/tradecore/config/GlobalExceptionHandler.java`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/io/github/ajayaj724/tradecore/config/SecurityConfigIT.java`

**Interfaces:**
- Produces: every request except `/actuator/health/**` requires a Bearer JWT; Keycloak realm roles arrive as `ROLE_TRADER` / `ROLE_OPS` / `ROLE_ADMIN`; 401/403/500 all render `application/problem+json`. Plan 1B's controllers assume exactly this.

- [ ] **Step 1: Write the failing IT**

```java
package io.github.ajayaj724.tradecore.config;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SecurityConfigIT {

  @Autowired MockMvc mvc;

  @Test
  void healthIsPublic() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void unauthenticatedGetsProblemJson401() throws Exception {
    mvc.perform(get("/api/v1/anything"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void authenticatedUnknownPathGets404NotAuthError() throws Exception {
    mvc.perform(get("/api/v1/anything").with(jwt().authorities(createAuthorityList("ROLE_TRADER"))))
        .andExpect(status().isNotFound());
  }
}
```

Run: `mvn verify -Dit.test=SecurityConfigIT` → FAIL (401 default body is empty, not problem+json; health may 401 depending on defaults). RED confirmed.

- [ ] **Step 2: Implement**

`SecurityConfig.java`:

```java
package io.github.ajayaj724.tradecore.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, ProblemDetailsAuthHandlers handlers)
      throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(
            org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth -> oauth
            .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoles()))
            .authenticationEntryPoint(handlers)
            .accessDeniedHandler(handlers))
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(handlers)
            .accessDeniedHandler(handlers))
        .build();
  }

  private static Converter<Jwt, AbstractAuthenticationToken> keycloakRealmRoles() {
    return jwt -> {
      Collection<GrantedAuthority> authorities = extractRealmRoles(jwt);
      return new JwtAuthenticationToken(jwt, authorities);
    };
  }

  @SuppressWarnings("unchecked")
  private static Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
      return List.of();
    }
    return roles.stream()
        .map(Object::toString)
        .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
        .toList();
  }
}
```

`ProblemDetailsAuthHandlers.java`:

```java
package io.github.ajayaj724.tradecore.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
class ProblemDetailsAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ObjectMapper mapper;

  ProblemDetailsAuthHandlers(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException ex) throws IOException {
    write(response, HttpStatus.UNAUTHORIZED, "Authentication required", request);
  }

  @Override
  public void handle(HttpServletRequest request, HttpServletResponse response,
      AccessDeniedException ex) throws IOException {
    write(response, HttpStatus.FORBIDDEN, "Access denied", request);
  }

  private void write(HttpServletResponse response, HttpStatus status, String detail,
      HttpServletRequest request) throws IOException {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setInstance(URI.create(request.getRequestURI()));
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(response.getWriter(), problem);
  }
}
```

`GlobalExceptionHandler.java`:

```java
package io.github.ajayaj724.tradecore.config;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** MVC exceptions render RFC 9457 via ResponseEntityExceptionHandler; domain handlers land in Plan 1B. */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {}
```

Add to `application.yaml`:

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8081/realms/tradecore
```

Note: `issuer-uri` triggers discovery at startup in prod runs; tests use the mock `jwt()` post-processor so no Keycloak is needed in CI. If context startup in ITs fails on issuer discovery, add to the IT `@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.public-key-location=classpath:test-jwt.pub")` — or simpler and preferred: in `src/test/resources/application.yaml` set `spring.security.oauth2.resourceserver.jwt.jwk-set-uri: http://localhost:9999/unused` (lazy — only hit on real token validation, which mock-jwt tests never do).

- [ ] **Step 3: Run to verify green**

Run: `mvn verify -Dit.test=SecurityConfigIT` → all three tests PASS.

- [ ] **Step 4: Manual end-to-end check against real Keycloak (record output for the debrief)**

```bash
docker compose up -d && mvn spring-boot:run &
sleep 25
TOKEN=$(curl -s -X POST http://localhost:8081/realms/tradecore/protocol/openid-connect/token \
  -d grant_type=password -d client_id=tradecore-api -d username=trader1 -d password=demo | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
curl -si http://localhost:8080/actuator/health | head -1          # HTTP/1.1 200
curl -si http://localhost:8080/api/v1/x | head -3                 # 401 + application/problem+json
curl -si -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/x | head -1  # 404 (authenticated, no route yet)
```

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply && mvn verify && git add -A && git commit -m "feat: OIDC resource server, Keycloak role mapping, RFC 9457 problem responses"
```

---

### Task 8: Observability — OTel pipeline + Grafana stack in compose

**Files:**
- Modify: `pom.xml` (observability dependencies)
- Modify: `src/main/resources/application.yaml`
- Modify: `compose.yaml` (add otel-collector, tempo, loki, prometheus, grafana)
- Create: `infra/otel/otel-collector.yaml`, `infra/prometheus/prometheus.yml`, `infra/tempo/tempo.yaml`, `infra/grafana/provisioning/datasources/datasources.yaml`

**Interfaces:**
- Produces: app exports OTLP traces to `localhost:4318` and Prometheus metrics at `/actuator/prometheus`; Grafana at `localhost:3000` (anonymous viewer) with Prometheus+Tempo+Loki datasources provisioned. Plan 1B's fill-latency metrics land on this pipeline unchanged.

- [ ] **Step 1: Add dependencies to `pom.xml`**

```xml
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-tracing-bridge-otel</artifactId></dependency>
    <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-exporter-otlp</artifactId></dependency>
    <dependency><groupId>org.springframework.modulith</groupId><artifactId>spring-modulith-observability</artifactId><scope>runtime</scope></dependency>
```

- [ ] **Step 2: Add to `application.yaml`**

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces
logging:
  structured:
    format:
      console: ecs
```

(`logging.structured.format.console=ecs` is Boot's built-in structured JSON logging with trace/span correlation fields — no logback XML needed. If local readability suffers during dev, comment it out locally, never in committed config.)

- [ ] **Step 3: Extend `compose.yaml`**

```yaml
  otel-collector:
    image: otel/opentelemetry-collector-contrib:latest
    command: ["--config=/etc/otel/config.yaml"]
    volumes:
      - ./infra/otel/otel-collector.yaml:/etc/otel/config.yaml:ro
    ports: ["4318:4318"]

  tempo:
    image: grafana/tempo:latest
    command: ["-config.file=/etc/tempo.yaml"]
    volumes:
      - ./infra/tempo/tempo.yaml:/etc/tempo.yaml:ro

  loki:
    image: grafana/loki:latest

  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports: ["9090:9090"]
    extra_hosts: ["host.docker.internal:host-gateway"]

  grafana:
    image: grafana/grafana:latest
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer
    volumes:
      - ./infra/grafana/provisioning:/etc/grafana/provisioning:ro
    ports: ["3000:3000"]
```

`infra/otel/otel-collector.yaml`:

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
exporters:
  otlp/tempo:
    endpoint: tempo:4317
    tls:
      insecure: true
processors:
  batch: {}
service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp/tempo]
```

`infra/tempo/tempo.yaml`:

```yaml
server:
  http_listen_port: 3200
distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
storage:
  trace:
    backend: local
    local:
      path: /tmp/tempo
```

`infra/prometheus/prometheus.yml`:

```yaml
scrape_configs:
  - job_name: tradecore
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["host.docker.internal:8080"]
```

Note: `/actuator/prometheus` is behind auth per Task 7 (only health is public). Either permit it for the docker network in SecurityConfig — add `.requestMatchers("/actuator/prometheus").permitAll()` with a code comment `// local-only compose scrape; lock down before any non-local deploy` — or configure Prometheus with a static bearer token. For Phase 1A choose the permitAll + comment; an ADR records the production answer (mTLS or token).

`infra/grafana/provisioning/datasources/datasources.yaml`:

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090
    isDefault: true
  - name: Tempo
    type: tempo
    url: http://tempo:3200
  - name: Loki
    type: loki
    url: http://loki:3100
```

- [ ] **Step 4: Verify the pipeline end-to-end**

```bash
docker compose up -d
mvn spring-boot:run &
sleep 25
TOKEN=$(curl -s -X POST http://localhost:8081/realms/tradecore/protocol/openid-connect/token \
  -d grant_type=password -d client_id=tradecore-api -d username=trader1 -d password=demo | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
for i in 1 2 3; do curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/ping; done
curl -s http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count | grep -q tradecore && echo METRICS-OK
curl -s "http://localhost:3200/api/search?limit=1" | grep -q traceID && echo TRACES-OK
```

Expected: `METRICS-OK` and `TRACES-OK`. Also open http://localhost:3000 → Explore → Tempo → search: the `/api/v1/ping` request trace (404 is fine — routing exists in Plan 1B; the trace is the point).

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply && mvn verify && git add -A && git commit -m "feat: OTel tracing + Prometheus metrics + Grafana stack in compose"
```

---

### Task 9: Dockerfile + GitHub Actions CI

**Files:**
- Create: `Dockerfile`, `.dockerignore`
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: non-root multi-stage image `tradecore:local`; CI = build+gate, CodeQL, Semgrep, gitleaks, Trivy. (OWASP Dependency-Check and SonarCloud join when the repo goes public — they need tokens/registration; tracked in README TODO section as explicit future work, not silence.)

- [ ] **Step 1: Create `Dockerfile`**

```dockerfile
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .mvn/ ./
COPY .mvn .mvn
RUN mvn -B dependency:go-offline
COPY src src
COPY config config
RUN mvn -B -DskipTests package spring-boot:repackage

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 1001 tradecore
USER 1001
WORKDIR /app
COPY --from=build /app/target/tradecore-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

(`-DskipTests` here is image assembly only — the gate has already run in the CI build job; the skill's no-skip rule applies to the gate, and the CI workflow enforces the ordering: the image job `needs: build`.)

`.dockerignore`:

```
target
.git
.idea
docs
```

- [ ] **Step 2: Build and smoke it**

```bash
docker build -t tradecore:local .
docker run --rm --network tradecore_default -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/tradecore \
  -e SPRING_DATASOURCE_USERNAME=tradecore -e SPRING_DATASOURCE_PASSWORD=tradecore \
  -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=http://keycloak:8080/realms/tradecore \
  -e MANAGEMENT_OTLP_TRACING_ENDPOINT=http://otel-collector:4318/v1/traces \
  -p 8082:8080 tradecore:local &
sleep 20 && curl -s http://localhost:8082/actuator/health | grep -q UP && echo IMAGE-OK
```

Expected: `IMAGE-OK`.

- [ ] **Step 3: Create `.github/workflows/ci.yml`**

```yaml
name: ci
on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: {distribution: temurin, java-version: "25", cache: maven}
      - run: mvn -B spotless:check verify

  codeql:
    runs-on: ubuntu-latest
    permissions: {security-events: write, contents: read}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: {distribution: temurin, java-version: "25", cache: maven}
      - uses: github/codeql-action/init@v3
        with: {languages: java}
      - run: mvn -B -DskipTests compile
      - uses: github/codeql-action/analyze@v3

  semgrep:
    runs-on: ubuntu-latest
    container: semgrep/semgrep
    steps:
      - uses: actions/checkout@v4
      - run: semgrep scan --config p/java --config p/spring --config p/owasp-top-ten --error

  gitleaks:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: {fetch-depth: 0}
      - uses: gitleaks/gitleaks-action@v2
        env: {GITHUB_TOKEN: "${{ secrets.GITHUB_TOKEN }}"}

  image:
    runs-on: ubuntu-latest
    needs: build
    steps:
      - uses: actions/checkout@v4
      - run: docker build -t tradecore:ci .
      - uses: aquasecurity/trivy-action@master
        with:
          image-ref: tradecore:ci
          exit-code: "1"
          severity: CRITICAL,HIGH
```

Note: CI cannot run until the repo has a GitHub remote — that's deliberate (publish decision is the owner's). The workflow is verified by `act` if installed, else on first push. gitleaks will flag the Keycloak demo password `demo` — add `.gitleaks.toml` allowlisting `infra/keycloak/tradecore-realm.json` with a comment explaining local-demo-only.

`.gitleaks.toml`:

```toml
[allowlist]
paths = ['''infra/keycloak/tradecore-realm\.json''']
description = "Local demo realm — intentional throwaway credentials, never used outside docker compose"
```

- [ ] **Step 4: Commit**

```bash
mvn spotless:apply && mvn verify && git add -A && git commit -m "ci: Dockerfile (non-root) + GitHub Actions — gate, CodeQL, Semgrep, gitleaks, Trivy"
```

---

### Task 10: README + full-gate closeout

**Files:**
- Create: `README.md`

**Interfaces:**
- Produces: the repo's front door; must reflect only what exists at this commit (no aspirational features).

- [ ] **Step 1: Write `README.md`** — sections, all describing current reality: what tradecore is (2 sentences + spec link); Quick start (`docker compose up -d`, `mvn spring-boot:run`, the Task 7 token curl, health/401/404 demo); Architecture (embed `docs/architecture/` Modulith-generated diagram reference + link to design spec); Quality (gate description, how to run, CI jobs list, explicit TODO list: Dependency-Check + SonarCloud on publish, dashboards in Phase 2, domain modules in Plan 1B); demo credentials warning (local only).

- [ ] **Step 2: Full gate + platform verification from clean state**

```bash
docker compose down -v && docker compose up -d && sleep 25
mvn spotless:apply && mvn clean verify
```

Expected: BUILD SUCCESS with all ITs green against fresh containers.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: README with quick start, architecture links, quality gate"
```

---

## Self-Review (done at authoring time)

- **Spec coverage (Phase 1A slice):** scaffold ✓(T2) gate ✓(T3, PIT/OWASP/Sonar explicitly deferred with tracking) modularity+ArchUnit ✓(T4) compose Postgres/Keycloak ✓(T5) Flyway+registry ✓(T6) security+RFC9457 ✓(T7) OTel/Grafana ✓(T8) Dockerfile+CI ✓(T9) README ✓(T10). Domain modules/engine/idempotency/audit → Plan 1B by design.
- **Known judgment calls recorded:** `/actuator/prometheus` permitAll with ADR note (T8); demo creds allowlisted with rationale (T9); JaCoCo Java-25 contingency (T1); issuer-uri vs test JWT decoding (T7).
- **Type consistency:** `TestcontainersConfig` consumed by T2/T6/T7 ITs; realm values (T5) consumed by T7/T8/T10 commands; base package consistent throughout.
