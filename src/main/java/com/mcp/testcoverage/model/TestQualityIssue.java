package com.mcp.testcoverage.model;

/**
 * An issue found in an existing test file during quality analysis.
 */
public record TestQualityIssue(
        String testFile,
        int lineNumber,
        String issueType,    // MISSING_ASSERTION, NO_MOCKITO, POOR_NAMING, EMPTY_TEST, etc.
        String severity,     // HIGH / MEDIUM / LOW
        String description,
        String suggestion
) {
    public String summary() {
        return "[%s] %s — line %d: %s".formatted(severity, issueType, lineNumber, description);
    }
}
