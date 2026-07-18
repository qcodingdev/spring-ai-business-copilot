package dev.qcoding.businesscopilot.resumecopilot;

import java.util.List;

/** 单份 JD、单份简历流程的精简公开契约。 */
public final class ResumeModels {
    private ResumeModels() {
    }

    public enum Status { CRITERIA_DRAFTED, CRITERIA_CONFIRMED, DRAFTED, NEEDS_REVIEW, REVIEWED, CANCELED, FAILED }
    public enum CriterionCategory { SKILL, EXPERIENCE, EDUCATION, CERTIFICATION, LANGUAGE, OTHER }
    public enum RequirementType { REQUIRED, PREFERRED }
    public enum MatchStatus { SUPPORTED, PARTIAL, NOT_FOUND, NEEDS_VERIFICATION }

    public record JobCriterion(String criterionId, CriterionCategory category, RequirementType requirementType,
                               String description, List<String> normalizedKeywords, String sourceText) {
        public JobCriterion {
            normalizedKeywords = normalizedKeywords == null ? List.of() : List.copyOf(normalizedKeywords);
        }
    }

    public record LlmJobCriteriaOutput(List<JobCriterion> criteria) {
        public LlmJobCriteriaOutput {
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
        }
    }

    public record ResumeEvidence(String evidenceId, String section, String sanitizedText, int positionIndex) {
    }

    public record CriterionAssessment(String criterionId, MatchStatus status, String explanation,
                                      List<String> evidenceIds) {
        public CriterionAssessment {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record InterviewQuestion(String criterionId, String question, List<String> evidenceIds) {
        public InterviewQuestion {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record AssessmentContent(String anonymousSummary, List<CriterionAssessment> criterionAssessments,
                                    List<String> evidenceGaps, List<InterviewQuestion> interviewQuestions,
                                    List<String> limitations) {
        public AssessmentContent {
            criterionAssessments = criterionAssessments == null ? List.of() : List.copyOf(criterionAssessments);
            evidenceGaps = evidenceGaps == null ? List.of() : List.copyOf(evidenceGaps);
            interviewQuestions = interviewQuestions == null ? List.of() : List.copyOf(interviewQuestions);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }
}
