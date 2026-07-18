package dev.qcoding.businesscopilot.resumecopilot.privacy;

import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeRepository;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/** Deletes expired sanitized resumes together with their evidence and assessments. */
public class ResumeRetentionService {

    private final ResumeRepository repository;

    public ResumeRetentionService(ResumeRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${business-copilot.resume-copilot.retention-cleanup-delay:PT1H}")
    public int deleteExpiredSubmissions() {
        return repository.deleteExpiredSubmissions(Instant.now());
    }
}
