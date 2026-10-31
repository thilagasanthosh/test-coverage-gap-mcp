package com.mcp.testcoverage.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.mcp.testcoverage.model.CoverageGap;
import com.mcp.testcoverage.model.TestQualityIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Uses JavaParser AST to find:
 *  1. Spring Boot classes (@RestController, @Service, @Component, @Repository) without matching test files
 *  2. Quality issues in existing test files (missing assertions, empty tests, poor naming)
 */
@Component
public class CoverageScanner {

    private static final Logger log = LoggerFactory.getLogger(CoverageScanner.class);

    // Spring stereotype annotations we consider "should be tested"
    private static final Set<String> SPRING_ANNOTATIONS = Set.of(
            "RestController", "Controller", "Service", "Component",
            "Repository", "EventListener", "Scheduled", "MessageMapping"
    );

    // Test annotations that indicate assertions/verification
    private static final Set<String> ASSERTION_METHODS = Set.of(
            "assertEquals", "assertNotNull", "assertNull", "assertTrue", "assertFalse",
            "assertThrows", "assertThat", "verify", "assertAll", "assertArrayEquals",
            "assertIterableEquals", "assertTimeout", "assertDoesNotThrow"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public record ScanResult(
            String scannedPath,
            int sourceFilesAnalysed,
            int testFilesFound,
            List<CoverageGap> gaps,
            String summary
    ) {}

    public record QualityResult(
            String testFile,
            int methodsAnalysed,
            List<TestQualityIssue> issues,
            String overallGrade,  // A / B / C / D / F
            String summary
    ) {}

    /**
     * Walk a project, find Spring Boot source classes, check whether matching test files exist.
     */
    public ScanResult findCoverageGaps(Path projectRoot) {
        // Collect all source .java files
        List<Path> sourceFiles = collectJavaFiles(projectRoot, false);
        List<Path> testFiles   = collectJavaFiles(projectRoot, true);

        // Index test file names for quick lookup
        Set<String> testFileNames = testFiles.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toSet());

        List<CoverageGap> gaps = new ArrayList<>();
        int sourceCount = 0;

        for (Path src : sourceFiles) {
            Optional<SpringClassInfo> info = parseSpringClass(src);
            if (info.isEmpty()) continue;
            sourceCount++;

            SpringClassInfo cls = info.get();
            String expectedTest = cls.className() + "Test.java";
            String altTest      = cls.className() + "Tests.java";

            boolean hasCoverage = testFileNames.contains(expectedTest)
                                   || testFileNames.contains(altTest);

            if (!hasCoverage) {
                gaps.add(new CoverageGap(
                        src.toString(),
                        cls.className(),
                        cls.classType(),
                        cls.packageName(),
                        "src/test/java/" + cls.packageName().replace('.', '/') + "/" + expectedTest,
                        cls.publicMethodCount(),
                        priorityFor(cls.classType(), cls.publicMethodCount())
                ));
            }
        }

        gaps.sort(Comparator.comparingInt(g -> -priorityOrder(g.priority())));

        String summary = gaps.isEmpty()
                ? "✅ All %d Spring Boot classes have matching test files.".formatted(sourceCount)
                : "⚠️ %d of %d classes have no test coverage.".formatted(gaps.size(), sourceCount);

        return new ScanResult(projectRoot.toString(), sourceCount, testFiles.size(), gaps, summary);
    }

    /**
     * Analyse the quality of a single test file — missing assertions, empty tests, etc.
     */
    public QualityResult analyseTestQuality(Path testFile) {
        List<TestQualityIssue> issues = new ArrayList<>();

        try {
            String content = Files.readString(testFile);
            String name    = testFile.getFileName().toString();

            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> result = parser.parse(testFile);
            if (result.getResult().isEmpty()) {
                return new QualityResult(testFile.toString(), 0, List.of(), "F",
                        "❌ Could not parse test file.");
            }

            CompilationUnit cu = result.getResult().get();
            List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);

            boolean hasTestClass = classes.stream().anyMatch(c ->
                    c.getAnnotationByName("SpringBootTest").isPresent()
                    || c.getAnnotationByName("ExtendWith").isPresent()
                    || c.getAnnotationByName("WebMvcTest").isPresent()
                    || c.getAnnotationByName("DataJpaTest").isPresent()
            );

            if (!hasTestClass) {
                issues.add(new TestQualityIssue(name, 1, "MISSING_TEST_ANNOTATION", "HIGH",
                        "No Spring test annotation (@SpringBootTest, @WebMvcTest, @ExtendWith) found.",
                        "Add @ExtendWith(MockitoExtension.class) for unit tests or @WebMvcTest for slice tests."));
            }

            List<MethodDeclaration> testMethods = cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getAnnotationByName("Test").isPresent()
                              || m.getAnnotationByName("ParameterizedTest").isPresent())
                    .collect(Collectors.toList());

            int methodCount = testMethods.size();

            for (MethodDeclaration method : testMethods) {
                String methodName = method.getNameAsString();
                String body       = method.toString();
                int line          = method.getRange().map(r -> r.begin.line).orElse(0);

                // Check for empty test body
                if (method.getBody().map(b -> b.getStatements().isEmpty()).orElse(true)) {
                    issues.add(new TestQualityIssue(name, line, "EMPTY_TEST", "HIGH",
                            "Test method '" + methodName + "' has no statements.",
                            "Write at least one Arrange-Act-Assert block."));
                    continue;
                }

                // Check for missing assertions
                boolean hasAssertion = ASSERTION_METHODS.stream()
                        .anyMatch(a -> body.contains(a + "("));
                if (!hasAssertion) {
                    issues.add(new TestQualityIssue(name, line, "MISSING_ASSERTION", "HIGH",
                            "Test '" + methodName + "' has no assertion or Mockito verify call.",
                            "Add assertEquals / assertThat / verify() to confirm expected behaviour."));
                }

                // Check for poor naming
                if (methodName.startsWith("test") && !methodName.contains("_") && !methodName.contains("should")
                        && !methodName.contains("when") && methodName.length() < 10) {
                    issues.add(new TestQualityIssue(name, line, "POOR_NAMING", "LOW",
                            "Test name '" + methodName + "' is not descriptive.",
                            "Use naming like 'shouldReturnUserWhenIdExists' or 'given_validId_when_getUser_then_returnsUser'."));
                }

                // Check for no Mockito when no @SpringBootTest
                if (!hasTestClass && !body.contains("mock(") && !body.contains("@Mock")
                        && !body.contains("Mockito") && !body.contains("when(")) {
                    issues.add(new TestQualityIssue(name, line, "NO_MOCK_SETUP", "MEDIUM",
                            "Test '" + methodName + "' has no Mockito setup — dependencies may not be stubbed.",
                            "Use @Mock + @InjectMocks, or use MockitoExtension with when(...).thenReturn(...)."));
                }
            }

            // File-level: no @Test methods at all
            if (methodCount == 0) {
                issues.add(new TestQualityIssue(name, 1, "NO_TEST_METHODS", "HIGH",
                        "No @Test or @ParameterizedTest methods found in this file.",
                        "Add test methods annotated with @Test from JUnit 5 (org.junit.jupiter.api.Test)."));
            }

            String grade = gradeFor(issues, methodCount);
            String summary = "Grade %s — %d test method(s), %d issue(s) found in %s"
                    .formatted(grade, methodCount, issues.size(), name);

            return new QualityResult(testFile.toString(), methodCount, issues, grade, summary);

        } catch (IOException e) {
            return new QualityResult(testFile.toString(), 0, List.of(), "F",
                    "❌ Could not read file: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private record SpringClassInfo(String className, String classType, String packageName, int publicMethodCount) {}

    private Optional<SpringClassInfo> parseSpringClass(Path file) {
        try {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (result.getResult().isEmpty()) return Optional.empty();

            CompilationUnit cu = result.getResult().get();
            String pkg = cu.getPackageDeclaration()
                    .map(p -> p.getNameAsString()).orElse("");

            List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
            for (ClassOrInterfaceDeclaration cls : classes) {
                if (cls.isInterface() || cls.isAbstract()) continue;

                Optional<AnnotationExpr> springAnnotation = cls.getAnnotations().stream()
                        .filter(a -> SPRING_ANNOTATIONS.contains(a.getNameAsString()))
                        .findFirst();

                if (springAnnotation.isPresent()) {
                    String type = mapAnnotationToType(springAnnotation.get().getNameAsString());
                    int methodCount = (int) cls.getMethods().stream()
                            .filter(m -> m.isPublic() && !m.isStatic())
                            .count();
                    return Optional.of(new SpringClassInfo(cls.getNameAsString(), type, pkg, methodCount));
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private List<Path> collectJavaFiles(Path root, boolean testTree) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (Set.of("target", "build", ".git", ".idea", "node_modules").contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // testTree=true → only descend into src/test; testTree=false → only src/main
                    boolean inTest = dir.toString().contains("/src/test/");
                    boolean inMain = dir.toString().contains("/src/main/");
                    if (testTree && inMain) return FileVisitResult.SKIP_SUBTREE;
                    if (!testTree && inTest) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                    if (file.getFileName().toString().endsWith(".java")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error walking {}: {}", root, e.getMessage());
        }
        return files;
    }

    private String mapAnnotationToType(String annotation) {
        return switch (annotation) {
            case "RestController", "Controller" -> "Controller";
            case "Repository"                   -> "Repository";
            case "Service"                      -> "Service";
            default                             -> "Component";
        };
    }

    private String priorityFor(String classType, int methodCount) {
        if ("Controller".equals(classType)) return "HIGH";
        if ("Service".equals(classType) && methodCount > 3) return "HIGH";
        if ("Service".equals(classType)) return "MEDIUM";
        if ("Repository".equals(classType)) return "MEDIUM";
        return "LOW";
    }

    private int priorityOrder(String p) {
        return switch (p) { case "HIGH" -> 3; case "MEDIUM" -> 2; default -> 1; };
    }

    private String gradeFor(List<TestQualityIssue> issues, int methodCount) {
        if (methodCount == 0) return "F";
        long highCount = issues.stream().filter(i -> "HIGH".equals(i.severity())).count();
        long medCount  = issues.stream().filter(i -> "MEDIUM".equals(i.severity())).count();
        if (highCount == 0 && medCount == 0) return "A";
        if (highCount == 0) return "B";
        if (highCount <= 1) return "C";
        if (highCount <= 3) return "D";
        return "F";
    }
}
