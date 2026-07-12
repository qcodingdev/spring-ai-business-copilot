package dev.qcoding.businesscopilot.resumecopilot.evidence;

import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;
import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;

import java.util.ArrayList;
import java.util.List;

/** Deterministically creates traceable evidence chunks from already-sanitized resume text. */
public class ResumeEvidenceService {
    private final ResumeCopilotProperties properties;

    public ResumeEvidenceService(ResumeCopilotProperties properties) {
        this.properties = properties;
    }

    public List<ResumeModels.ResumeEvidence> extract(String sanitizedResume) {
        List<ResumeModels.ResumeEvidence> evidence = new ArrayList<>();
        String section = "GENERAL";
        int position = 0;
        for (String line : sanitizedResume.split("\n")) {
            String text = line.trim();
            if (text.isBlank()) continue;
            if (text.startsWith("#")) {
                section = text.replaceFirst("^#+\\s*", "").trim();
                continue;
            }
            evidence.add(new ResumeModels.ResumeEvidence("evidence-" + (evidence.size() + 1), section, text, position++));
            if (evidence.size() >= properties.maxEvidenceCount()) break;
        }
        return List.copyOf(evidence);
    }
}
