package dev.qcoding.businesscopilot.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一次案例执行的完整轨迹（EVAL-03 过程与结果分别检查）。
 *
 * <p>轨迹记录关键动作与业务状态快照：断言据此判定，不要求所有正确任务遵循
 * 完全相同的工具顺序。轨迹不保存敏感全文，只保存动作类别、对象标识与结果状态。</p>
 */
public final class ExecutionTrace {

    /** 轨迹中的一个动作：工具调用、状态转换、模型调用或外部动作。 */
    public record Action(String type, String targetRef, String outcome, Map<String, String> details) {
        public Action {
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    private final List<Action> actions = new ArrayList<>();
    private final Map<String, String> finalState = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();

    public void action(String type, String targetRef, String outcome) {
        actions.add(new Action(type, targetRef, outcome, Map.of()));
    }

    public void action(String type, String targetRef, String outcome, Map<String, String> details) {
        actions.add(new Action(type, targetRef, outcome, details));
    }

    public void state(String key, String value) {
        finalState.put(key, value);
    }

    public void note(String note) {
        notes.add(note);
    }

    public List<Action> actions() {
        return List.copyOf(actions);
    }

    public Map<String, String> finalState() {
        return Map.copyOf(finalState);
    }

    public List<String> notes() {
        return List.copyOf(notes);
    }

    public Optional<String> state(String key) {
        return Optional.ofNullable(finalState.get(key));
    }

    /** 是否发生过指定类型且命中指定对象的动作。 */
    public boolean performed(String actionType, String targetRef) {
        return actions.stream().anyMatch(a -> a.type().equals(actionType)
                && (targetRef == null || a.targetRef().equals(targetRef)));
    }

    /** 指定对象上指定动作的发生次数。 */
    public long count(String actionType, String targetRef) {
        return actions.stream().filter(a -> a.type().equals(actionType)
                && (targetRef == null || a.targetRef().equals(targetRef))).count();
    }
}
