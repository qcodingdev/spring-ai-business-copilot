package dev.qcoding.businesscopilot.demo;

import java.util.List;

/** 服务端场景完整模型；dataScopeJson 永不直接返回普通用户。 */
public record DemoScenario(
        String scenarioId,
        DemoModule module,
        String title,
        String description,
        String inputTemplate,
        List<DemoOperation> allowedOperations,
        String dataScopeJson,
        String dataScopeLabel,
        int version,
        boolean enabled,
        boolean systemManaged,
        boolean fallbackResultAvailable,
        String contentHash) {

    public ScenarioProjection projection() {
        return new ScenarioProjection(
                scenarioId, module, title, description, inputTemplate,
                allowedOperations, dataScopeLabel, version, fallbackResultAvailable);
    }

    /** 普通用户仅能看到的安全投影。 */
    public record ScenarioProjection(
            String scenarioId,
            DemoModule module,
            String title,
            String description,
            String inputTemplate,
            List<DemoOperation> allowedOperations,
            String dataScope,
            int version,
            boolean fallbackResultAvailable) {
    }
}
