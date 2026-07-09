package dev.qcoding.businesscopilot.supportcopilot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Support Copilot module.
 *
 * <p>Support Copilot 模块级配置。控制模块开关、工单长度上限、草稿过期时间、
 * 高风险分类列表、自动转人工开关和知识检索 topK 参数。</p>
 *
 * @param enabled                 whether the Support Copilot feature is active
 * @param maxTicketLength         maximum accepted customer message length in characters
 * @param draftTtlMinutes         reply draft TTL in minutes before expiry
 * @param highRiskCategories      comma-separated list of categories that default to needsHuman
 * @param autoHumanHandoffEnabled whether to auto-suggest human handoff for high-risk categories
 * @param knowledgeTopK           number of knowledge chunks to retrieve per ticket
 */
@ConfigurationProperties(prefix = "business-copilot.support-copilot")
public record SupportCopilotProperties(
        boolean enabled,
        int maxTicketLength,
        int draftTtlMinutes,
        String highRiskCategories,
        boolean autoHumanHandoffEnabled,
        int knowledgeTopK) {

    public SupportCopilotProperties {
        if (maxTicketLength <= 0) {
            maxTicketLength = 2000;
        }
        if (draftTtlMinutes <= 0) {
            draftTtlMinutes = 10;
        }
        if (highRiskCategories == null || highRiskCategories.isBlank()) {
            highRiskCategories = "REFUND,ACCOUNT_SECURITY,INCIDENT";
        }
        if (knowledgeTopK <= 0) {
            knowledgeTopK = 5;
        }
    }
}
