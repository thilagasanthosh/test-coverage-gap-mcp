# test-coverage-gap-mcp

> MCP server that identifies untested Spring Boot code and generates JUnit 5 test skeletons. Uses AST analysis via JavaParser to understand Spring annotations — not just filename matching.

## Tools

| Tool | Description |
|------|-------------|
| `findCoverageGaps` | Find `@RestController`, `@Service`, `@Component`, `@Repository` classes with no test file |
| `analyseTestQuality` | Assess existing test files for missing assertions, empty tests, poor naming |
| `suggestTests` | Generate a JUnit 5 + Mockito test skeleton for any untested class |

## Usage with Claude

### HTTP mode (Claude Code)
```bash
mvn spring-boot:run
```

### Stdio mode (Claude Desktop)
```bash
mvn spring-boot:run --spring.profiles.active=stdio
```

## Example Conversations

### Find untested classes
> "Find all untested classes in my project at /Users/me/myapp"

```
⚠️ 5 of 12 classes have no test coverage.

🔴 HIGH   UserController [Controller] — 6 method(s) untested
          Expected: src/test/java/com/example/UserControllerTest.java

🔴 HIGH   OrderService [Service] — 8 method(s) untested
          Expected: src/test/java/com/example/OrderServiceTest.java

🟠 MEDIUM ProductRepository [Repository] — 3 method(s) untested
          Expected: src/test/java/com/example/ProductRepositoryTest.java
```

### Analyse test quality
> "Check the quality of tests in /Users/me/myapp/src/test"

```
Grade C — UserServiceTest.java
  🔴 HIGH  MISSING_ASSERTION — shouldSaveUser(): no assertEquals/assertThat/verify found
  🟡 LOW   POOR_NAMING — test1(): not descriptive. Use 'shouldReturnUserWhenIdExists'
```

### Generate test skeleton
> "Generate a test for /Users/me/myapp/src/main/java/com/example/UserService.java"

Claude generates a ready-to-compile test class:
```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserService subject;

    // TODO: Add @Mock for each dependency

    @Test
    void shouldSuccessfullyCall_findById() {
        // Given
        // TODO: set up inputs and mock dependencies

        // When
        // var result = subject.findById(/* args */);

        // Then
        // assertThat(result).isNotNull();
    }
}
```

## Prioritisation

| Class Type | Priority | Why |
|-----------|----------|-----|
| `@RestController` / `@Controller` | 🔴 HIGH | Public API — directly reachable by users |
| `@Service` with > 3 methods | 🔴 HIGH | Core business logic |
| `@Service` with ≤ 3 methods | 🟠 MEDIUM | Business logic |
| `@Repository` | 🟠 MEDIUM | Data access — important edge cases |
| `@Component` | 🟡 LOW | Infrastructure/utility |

## Test Quality Grades

| Grade | Meaning |
|-------|---------|
| A | No issues — all tests have assertions and clear names |
| B | Minor issues only (naming, low-severity) |
| C | 1 HIGH issue (e.g. one test missing assertion) |
| D | 2–3 HIGH issues |
| F | No test methods, or multiple critical failures |

## Test Slice Selection

The `suggestTests` tool chooses the right Spring Boot test slice automatically:

| Source Annotation | Generated Test Annotation |
|-------------------|--------------------------|
| `@RestController` | `@WebMvcTest` + `MockMvc` |
| `@Repository` | `@DataJpaTest` (embedded H2) |
| `@Service`, `@Component` | `@ExtendWith(MockitoExtension.class)` + `@Mock` |

## Requirements

- Java 21+
- Maven 3.9+
- Spring Boot 3.5.0
- Spring AI 1.0.0 MCP Server
- JavaParser 3.26.2 (included in pom.xml)

## Build

```bash
mvn clean package
java -jar target/test-coverage-gap-mcp-0.0.1-SNAPSHOT.jar
```

## Docker

```bash
docker build -t test-coverage-gap-mcp .
docker run -p 8080:8080 test-coverage-gap-mcp
```

## Claude Desktop Config

```json
{
  "mcpServers": {
    "test-coverage-gap": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/test-coverage-gap-mcp-0.0.1-SNAPSHOT.jar",
        "--spring.profiles.active=stdio"
      ]
    }
  }
}
```

## How It Works

1. **Coverage gaps**: Uses JavaParser AST to read each `.java` file, find Spring stereotype annotations (`@RestController`, `@Service` etc.) and count public methods. Then checks whether a `*Test.java` or `*Tests.java` file exists in the corresponding `src/test/` location.

2. **Quality analysis**: Parses test files with JavaParser, checks each `@Test` method for assertions (using a comprehensive list of JUnit 5 + AssertJ + Mockito assertion method names), empty bodies, and poor naming conventions.

3. **Test generation**: Reads the source file, detects the Spring type, extracts public method names, and emits a compilable JUnit 5 skeleton following the Given-When-Then pattern with the correct test slice annotation.

## Related Projects

- [secret-scanner-mcp](../secret-scanner-mcp) — Detect secrets and credentials in source code
- [owasp-checker-mcp](../owasp-checker-mcp) — OWASP Top 10 AST analysis for Spring Boot
- [jwt-analyser-mcp](../jwt-analyser-mcp) — Decode and audit JWT tokens
- [http-security-headers-mcp](../http-security-headers-mcp) — HTTP security header analysis
- [dependency-vuln-mcp](../dependency-vuln-mcp) — CVE scanning via OSV.dev
