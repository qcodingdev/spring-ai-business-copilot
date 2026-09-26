package dev.qcoding.businesscopilot.supportcopilot.ticket;

/**
 * SUP-02：转人工原因分类，每类给出可执行的下一步。
 *
 * <p>原因由服务端按分支确定性标注：证据不足、证据过期、风险承诺、权限限制、工具失败。
 * 原因随工单持久化并可查询，不暴露模型内部推理。</p>
 */
public enum SupportHandoffReason {
    NO_EVIDENCE("补充知识库内容，或由人工直接回复客户"),
    EVIDENCE_EXPIRED("刷新知识来源后重新分析，或由人工核实最新资料"),
    RISKY_PROMISE("人工审核草稿中的风险承诺并修订后再回复"),
    PERMISSION_LIMIT("由具备权限的坐席或管理员处理该类请求"),
    TOOL_FAILURE("排查模型或外部工具故障后重试分析");

    private final String nextStep;

    SupportHandoffReason(String nextStep) {
        this.nextStep = nextStep;
    }

    public String nextStep() {
        return nextStep;
    }
}
