package dev.qcoding.businesscopilot.evaluation;

import java.util.List;

/**
 * 失败案例与数据集管理（EVAL-06）。
 *
 * <p>区分开发调优集（dev）与保留验证集（holdout）：开发集用于迭代修复，
 * 保留验证集用于验证没有针对单个案例过拟合。所有案例必须使用虚构或经授权脱敏数据。</p>
 */
public record EvaluationDataset(String name, String version, Split dev, Split holdout) {

    public EvaluationDataset {
        if (dev == null) dev = Split.empty();
        if (holdout == null) holdout = Split.empty();
    }

    /** 一组案例及其用途说明。 */
    public record Split(List<EvaluationCase> cases, String purpose) {
        public Split {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }

        public static Split empty() {
            return new Split(List.of(), "无案例");
        }
    }

    public List<EvaluationCase> allCases() {
        List<EvaluationCase> all = new java.util.ArrayList<>(dev.cases());
        all.addAll(holdout.cases());
        return List.copyOf(all);
    }
}
