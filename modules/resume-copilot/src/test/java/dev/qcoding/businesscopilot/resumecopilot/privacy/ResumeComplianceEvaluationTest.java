package dev.qcoding.businesscopilot.resumecopilot.privacy;

import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeComplianceEvaluationTest {

    @Test
    void fixedJobComplianceSetRemainsStable() throws Exception {
        var resource = getClass().getResourceAsStream("/evals/job-compliance.tsv");
        assertThat(resource).isNotNull();
        List<String> lines = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertThat(lines).as("Resume 固定评测集不能缩减到 10 条以下").hasSizeGreaterThanOrEqualTo(10);
        ResumePrivacySanitizer sanitizer = new ResumePrivacySanitizer(
                new ResumeCopilotProperties(true, 12000, 20000, 30, 80,
                        Duration.ofMinutes(30), true));

        for (String line : lines) {
            String[] fields = line.split("\\t", 2);
            boolean accepted;
            try {
                sanitizer.sanitizeJobDescription(fields[1]);
                accepted = true;
            } catch (RuntimeException ex) {
                accepted = false;
            }
            assertThat(accepted).as(line).isEqualTo(Boolean.parseBoolean(fields[0]));
        }
    }
}
