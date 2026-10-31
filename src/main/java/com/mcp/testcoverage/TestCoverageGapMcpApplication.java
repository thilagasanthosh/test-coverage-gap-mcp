package com.mcp.testcoverage;

import com.mcp.testcoverage.tool.TestCoverageTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Test Coverage Gap MCP Server
 *
 * Exposes MCP tools to Claude for identifying untested Spring Boot code:
 *   - findCoverageGaps    : find @RestController / @Service classes without matching test files
 *   - analyseTestQuality  : assess the quality of existing test files (assertions, mocking, naming)
 *   - suggestTests        : generate JUnit 5 test skeletons for an untested class
 */
@SpringBootApplication
public class TestCoverageGapMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestCoverageGapMcpApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider testCoverageToolProvider(TestCoverageTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
