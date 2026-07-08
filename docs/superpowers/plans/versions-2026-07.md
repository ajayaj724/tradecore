# Verified Dependency Versions (2026-07)

Fetched on 2026-07-06 from Maven Central (repo1.maven.org). Ground truth for Tasks 2–3 property blocks.

## Version Matrix

| Artifact | Verified Version | Baseline | Status |
|----------|------------------|----------|--------|
| `org.springframework.boot:spring-boot-starter-parent` | 4.1.0 | 4.1.0 | ✓ GA |
| `org.springframework.modulith:spring-modulith-bom` | 2.1.0 | 2.1.0 | ✓ GA |
| `com.diffplug.spotless:spotless-maven-plugin` | 3.8.0 | 2.46.1 | ✓ GA, +upgrade |
| `com.google.errorprone:error_prone_core` | 2.50.0 | 2.42.0 | ✓ GA, +upgrade |
| `com.uber.nullaway:nullaway` | 0.13.7 | 0.12.10 | ✓ GA, +upgrade |
| `org.apache.maven.plugins:maven-checkstyle-plugin` | 3.6.0 | 3.6.0 | ✓ GA |
| `com.puppycrawl.tools:checkstyle` | 13.7.0 | 10.26.1 | ✓ GA, +major upgrade |
| `org.jacoco:jacoco-maven-plugin` | 0.8.15 | 0.8.13 | ✓ GA, +upgrade |
| `com.tngtech.archunit:archunit-junit5` | 1.4.2 | 1.4.1 | ✓ GA, +upgrade |

## Notes

- **Spotless**: 3.8.0 is latest GA. Baseline was 2.46.1 (major version drift).
- **Error Prone**: 2.50.0 is latest GA. Baseline was 2.42.0 (+8 releases).
- **NullAway**: 0.13.7 is latest GA. Baseline was 0.12.10 (0.13.x branch is current).
- **Checkstyle**: 13.7.0 is latest GA. Baseline was 10.26.1 (major version 13 now current; metadata path is `/com/puppycrawl/tools/checkstyle/`, not under spotless).
- **JaCoCo**: 0.8.15 is latest GA. The Java 25 bytecode contingency did not trigger: 0.8.15
  handles Java 25 bytecode correctly, and the `pom.xml` coverage rule enforces FAIL (not
  WARN) at the 80% line minimum. See GitHub issue #2076 for Java 26 support status.
- **ArchUnit**: 1.4.2 is latest GA. Baseline was 1.4.1 (patch-level upgrade).
- All verified versions are GA releases. No milestone/RC candidates found with higher version numbers.

## Fetch Command

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
  curl -s "https://repo1.maven.org/maven2/$gav/maven-metadata.xml" | grep -E '<release>'
done
```

Source: Maven Central metadata.xml `<release>` tags verified 2026-07-06.
