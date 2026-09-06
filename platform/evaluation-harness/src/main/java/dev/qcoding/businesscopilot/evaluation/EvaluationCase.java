package dev.qcoding.businesscopilot.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一的任务级评测案例描述（EVAL-01）。
 *
 * <p>每个案例包含：输入、操作者（角色）、初始数据、预期结果断言、禁止行为、预算。
 * 案例数据必须是虚构或经授权脱敏的内容，不得直接复制真实简历、客户或密钥内容（EVAL-06）。</p>
 *
 * @param caseId            稳定案例编号（如 X-01）
 * @param module            所属模块
 * @param scenario          场景描述（人类可读）
 * @param operatorActorId   操作者标识
 * @param operatorRoles     操作者业务角色
 * @param input             案例输入（键值对，由执行器解释）
 * @param initialData       初始数据（测试环境准备时写入）
 * @param assertions        过程与结果断言（EVAL-03：结果与安全分别检查）
 * @param forbiddenBehaviors 禁止行为清单（安全违规不可被质量分数抵消）
 * @param maxModelCalls     案例允许的最大模型调用次数；null 表示不限制
 * @param cleanupNotes      清理要求：只清理本次创建的测试资源
 */
public record EvaluationCase(
        String caseId,
        String module,
        String scenario,
        String operatorActorId,
        Set<String> operatorRoles,
        Map<String, String> input,
        Map<String, String> initialData,
        List<EvaluationAssertion> assertions,
        List<String> forbiddenBehaviors,
        Integer maxModelCalls,
        String cleanupNotes) {

    public EvaluationCase {
        input = input == null ? Map.of() : Map.copyOf(input);
        initialData = initialData == null ? Map.of() : Map.copyOf(initialData);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        forbiddenBehaviors = forbiddenBehaviors == null ? List.of() : List.copyOf(forbiddenBehaviors);
        operatorRoles = operatorRoles == null ? Set.of() : Set.copyOf(operatorRoles);
        cleanupNotes = cleanupNotes == null || cleanupNotes.isBlank()
                ? "运行结束后仅清理本次创建的测试资源" : cleanupNotes;
    }

    /** 是否包含安全类断言（门禁：安全场景必须全部通过）。 */
    public boolean hasSafetyAssertions() {
        return assertions.stream().anyMatch(a -> a.kind() == EvaluationAssertion.Kind.SAFETY);
    }

    /** Builder：保证案例描述完整可读。 */
    public static Builder builder(String caseId, String module, String scenario) {
        return new Builder(caseId, module, scenario);
    }

    public static final class Builder {

        private final String caseId;
        private final String module;
        private final String scenario;
        private final Map<String, String> input = new LinkedHashMap<>();
        private final Map<String, String> initialData = new LinkedHashMap<>();
        private final List<EvaluationAssertion> assertions = new java.util.ArrayList<>();
        private final List<String> forbiddenBehaviors = new java.util.ArrayList<>();
        private String operatorActorId = "operator-eval";
        private Set<String> operatorRoles = Set.of("OPERATOR");
        private Integer maxModelCalls;
        private String cleanupNotes;

        private Builder(String caseId, String module, String scenario) {
            this.caseId = caseId;
            this.module = module;
            this.scenario = scenario;
        }

        public Builder operator(String actorId, String... roles) {
            this.operatorActorId = actorId;
            this.operatorRoles = Set.of(roles);
            return this;
        }

        public Builder input(String key, String value) {
            input.put(key, value);
            return this;
        }

        public Builder initialData(String key, String value) {
            initialData.put(key, value);
            return this;
        }

        public Builder assertResult(String description, EvaluationAssertion.Check check) {
            assertions.add(new EvaluationAssertion(EvaluationAssertion.Kind.RESULT, description, check));
            return this;
        }

        public Builder assertProcess(String description, EvaluationAssertion.Check check) {
            assertions.add(new EvaluationAssertion(EvaluationAssertion.Kind.PROCESS, description, check));
            return this;
        }

        public Builder assertSafety(String description, EvaluationAssertion.Check check) {
            assertions.add(new EvaluationAssertion(EvaluationAssertion.Kind.SAFETY, description, check));
            return this;
        }

        public Builder forbidden(String behavior) {
            forbiddenBehaviors.add(behavior);
            return this;
        }

        public Builder maxModelCalls(int max) {
            this.maxModelCalls = max;
            return this;
        }

        public Builder cleanup(String notes) {
            this.cleanupNotes = notes;
            return this;
        }

        public EvaluationCase build() {
            return new EvaluationCase(caseId, module, scenario, operatorActorId, operatorRoles,
                    input, initialData, assertions, forbiddenBehaviors, maxModelCalls, cleanupNotes);
        }
    }
}
