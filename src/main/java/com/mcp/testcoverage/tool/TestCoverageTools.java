package com.mcp.testcoverage.tool;

import com.mcp.testcoverage.model.CoverageGap;
import com.mcp.testcoverage.scanner.CoverageScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP tools for identifying test coverage gaps in Spring Boot projects.
 *
 * Three tools:
 *  1. findCoverageGaps    — find Spring Boot classes with no test files
 *  2. analyseTestQuality  — assess quality of existing tests (assertions, mocking, naming)
 *  3. suggestTests        — generate a JUnit 5 test skeleton for an untested class
 */
@Component
public class TestCoverageTools {

    private static final Logger log = LoggerFactory.getLogger(TestCoverageTools.class);
    private final CoverageScanner scanner;

    public TestCoverageTools(CoverageScanner scanner) {
        this.scanner = scanner;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 1 — Find untested classes
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = """
            Scan a Spring Boot project and find classes annotated with @RestController, @Service,
            @Component or @Repository that have no matching test file in src/test/.
            Prioritises controllers (HIGH) over services (MEDIUM) over components (LOW).
            Returns a list of coverage gaps with the expected test file path and method count.
            """)
    public CoverageGapReport findCoverageGaps(
            @ToolParam(description = "Absolute path to the Spring Boot project root, e.g. /Users/me/myapp")
            String projectPath
    ) {
        log.info("Finding coverage gaps in: {}", projectPath);
        Path root = Path.of(projectPath);

        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return new CoverageGapReport(projectPath, 0, 0, List.of(),
                    "❌ Path not found or not a directory: " + projectPath);
        }

        CoverageScanner.ScanResult result = scanner.findCoverageGaps(root);

        return new CoverageGapReport(
                result.scannedPath(),
                result.sourceFilesAnalysed(),
                result.testFilesFound(),
                result.gaps(),
                result.summary()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 2 — Analyse test quality
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = """
            Analyse the quality of existing test files in a project or a specific test file.
            Checks for: missing assertions, empty @Test methods, no Mockito mocking, poor test naming.
            Assigns a grade (A-F) and returns specific issues with suggestions to fix them.
            Provide either a project root (scans all test files) or a specific test file path.
            """)
    public TestQualityReport analyseTestQuality(
            @ToolParam(description = "Absolute path to a test file or project root, e.g. /Users/me/myapp or /Users/me/myapp/src/test/java/com/example/UserServiceTest.java")
            String path
    ) {
        log.info("Analysing test quality at: {}", path);
        Path target = Path.of(path);

        if (!Files.exists(target)) {
            return new TestQualityReport(path, List.of(), "❌ Path not found: " + path);
        }

        if (Files.isRegularFile(target)) {
            // Single file
            CoverageScanner.QualityResult result = scanner.analyseTestQuality(target);
            return new TestQualityReport(path, List.of(result), result.summary());
        }

        // Directory — find all test files
        List<CoverageScanner.QualityResult> results;
        try {
            results = Files.walk(target)
                    .filter(p -> p.toString().contains("/src/test/") && p.toString().endsWith("Test.java"))
                    .map(scanner::analyseTestQuality)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return new TestQualityReport(path, List.of(), "❌ Walk failed: " + e.getMessage());
        }

        if (results.isEmpty()) {
            return new TestQualityReport(path, List.of(), "No test files found under: " + path);
        }

        long failing = results.stream().filter(r -> List.of("D", "F").contains(r.overallGrade())).count();
        String summary = "%d test file(s) analysed. %d need attention (grade D or F)."
                .formatted(results.size(), failing);

        return new TestQualityReport(path, results, summary);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 3 — Suggest tests
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = """
            Generate a JUnit 5 + Mockito test skeleton for an untested Spring Boot class.
            Reads the source file, identifies public methods, and produces a compilable test class
            with @Test method stubs following the Given-When-Then pattern.
            Provide the absolute path to the .java source file.
            """)
    public TestSuggestion suggestTests(
            @ToolParam(description = "Absolute path to the Spring Boot source .java file to generate tests for")
            String sourceFilePath
    ) {
        log.info("Generating test suggestions for: {}", sourceFilePath);
        Path src = Path.of(sourceFilePath);

        if (!Files.exists(src)) {
            return new TestSuggestion(sourceFilePath, "❌ File not found: " + sourceFilePath, null);
        }

        try {
            String content    = Files.readString(src);
            String fileName   = src.getFileName().toString();
            String className  = fileName.replace(".java", "");
            String pkg        = extractPackage(content);
            String testPkg    = pkg; // test in same package

            // Detect Spring type for test slice annotation
            boolean isController = content.contains("@RestController") || content.contains("@Controller");
            boolean isRepository = content.contains("@Repository");
            boolean isService    = content.contains("@Service") || content.contains("@Component");

            // Extract public method signatures
            List<String> methods = extractPublicMethods(content);

            String testClass = buildTestSkeleton(
                    className, testPkg, isController, isRepository, isService, methods, content);

            String note = isController
                    ? "Controller test — uses @WebMvcTest + MockMvc. Run with Maven: mvn test"
                    : isRepository
                    ? "Repository test — uses @DataJpaTest with embedded H2 database."
                    : "Service/Component test — uses @ExtendWith(MockitoExtension.class) with @Mock.";

            return new TestSuggestion(sourceFilePath, note, testClass);

        } catch (IOException e) {
            return new TestSuggestion(sourceFilePath, "❌ Could not read file: " + e.getMessage(), null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result records
    // ─────────────────────────────────────────────────────────────────────────

    public record CoverageGapReport(
            String projectPath,
            int sourceFilesAnalysed,
            int testFilesFound,
            List<CoverageGap> gaps,
            String summary
    ) {}

    public record TestQualityReport(
            String path,
            List<CoverageScanner.QualityResult> results,
            String summary
    ) {}

    public record TestSuggestion(
            String sourceFile,
            String note,
            String testClassCode
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Code generation helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildTestSkeleton(String className, String pkg,
            boolean isController, boolean isRepository, boolean isService,
            List<String> methods, String originalSource) {

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        if (isController) {
            sb.append("""
                    import org.junit.jupiter.api.Test;
                    import org.springframework.beans.factory.annotation.Autowired;
                    import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
                    import org.springframework.boot.test.mock.mockito.MockBean;
                    import org.springframework.test.web.servlet.MockMvc;
                    import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
                    import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
                    import static org.mockito.Mockito.*;
                    import static org.assertj.core.api.Assertions.*;

                    @WebMvcTest(""").append(className).append(""".class)
                    class """).append(className).append("""
                    Test {

                        @Autowired
                        private MockMvc mockMvc;

                        // TODO: Add @MockBean for each service dependency of """)
                    .append(className).append("\n\n");
        } else if (isRepository) {
            sb.append("""
                    import org.junit.jupiter.api.Test;
                    import org.springframework.beans.factory.annotation.Autowired;
                    import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
                    import static org.assertj.core.api.Assertions.*;

                    @DataJpaTest
                    class """).append(className).append("""
                    Test {

                        @Autowired
                        private """).append(className).append(" repository;\n\n");
        } else {
            sb.append("""
                    import org.junit.jupiter.api.Test;
                    import org.junit.jupiter.api.extension.ExtendWith;
                    import org.mockito.InjectMocks;
                    import org.mockito.Mock;
                    import org.mockito.junit.jupiter.MockitoExtension;
                    import static org.assertj.core.api.Assertions.*;
                    import static org.mockito.Mockito.*;

                    @ExtendWith(MockitoExtension.class)
                    class """).append(className).append("""
                    Test {

                        @InjectMocks
                        private """).append(className).append(" subject;\n\n")
                    .append("        // TODO: Add @Mock for each dependency\n\n");
        }

        if (methods.isEmpty()) {
            sb.append("    @Test\n")
              .append("    void shouldInitialiseCorrectly() {\n")
              .append("        // TODO: write your first test\n")
              .append("        assertThat(subject).isNotNull();\n")
              .append("    }\n");
        } else {
            for (String method : methods) {
                String testName = "shouldSuccessfullyCall_" + method;
                sb.append("    @Test\n")
                  .append("    void ").append(testName).append("() {\n")
                  .append("        // Given\n")
                  .append("        // TODO: set up inputs and mock dependencies\n\n")
                  .append("        // When\n")
                  .append("        // var result = subject.").append(method).append("(/* args */);\n\n")
                  .append("        // Then\n")
                  .append("        // assertThat(result).isNotNull();\n")
                  .append("    }\n\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    private String extractPackage(String source) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                return trimmed.replace("package ", "").replace(";", "").trim();
            }
        }
        return "com.example";
    }

    private List<String> extractPublicMethods(String source) {
        List<String> methods = new java.util.ArrayList<>();
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            // Match: public <type> <name>(
            if (trimmed.startsWith("public ") && trimmed.contains("(")
                    && !trimmed.contains("class ") && !trimmed.contains("interface ")
                    && !trimmed.startsWith("public class") && !trimmed.startsWith("public interface")
                    && !trimmed.contains("static")) {
                int open  = trimmed.indexOf('(');
                int space = trimmed.lastIndexOf(' ', open);
                if (space > 0) {
                    String methodName = trimmed.substring(space + 1, open).trim();
                    if (!methodName.isEmpty() && methodName.matches("[a-zA-Z][a-zA-Z0-9]*")) {
                        methods.add(methodName);
                    }
                }
            }
        }
        return methods.stream().distinct().limit(10).collect(Collectors.toList());
    }
}
