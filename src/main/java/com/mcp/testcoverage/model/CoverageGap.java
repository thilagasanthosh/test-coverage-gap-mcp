package com.mcp.testcoverage.model;

/**
 * Represents a Spring Boot class that has no corresponding test file.
 */
public record CoverageGap(
        String sourceFile,
        String className,
        String classType,        // "Controller", "Service", "Repository", "Component"
        String packageName,
        String expectedTestFile, // where the test SHOULD be
        int methodCount,         // number of public methods detected
        String priority          // HIGH / MEDIUM / LOW
) {
    public String summary() {
        return "%s [%s] — %d method(s) untested. Expected test: %s"
                .formatted(className, classType, methodCount, expectedTestFile);
    }

    public String priorityEmoji() {
        return switch (priority) {
            case "HIGH"   -> "🔴";
            case "MEDIUM" -> "🟠";
            default       -> "🟡";
        };
    }
}
