package dev.qcoding.businesscopilot.supportcopilot.draft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SupportFollowUpServiceTest {

    private final SupportFollowUpService service = new SupportFollowUpService();

    @Test
    void suggestsQuestionsForMissingEssentials() {
        // SUP-01：缺少单号、时间、报错信息、操作步骤 → 全部追问。
        List<String> questions = service.suggestFollowUps("软件用不了了，请尽快帮我处理。");

        assertThat(questions).hasSize(4);
        assertThat(questions.get(0)).contains("订单号或工单号");
        assertThat(questions.get(1)).contains("时间");
        assertThat(questions.get(2)).contains("报错");
        assertThat(questions.get(3)).contains("操作步骤");
    }

    @Test
    void skipsQuestionsForElementsAlreadyPresent() {
        List<String> questions = service.suggestFollowUps(
                "昨天 14 点在 App 下单支付时页面报错，订单号 20260828001，操作步骤是点支付后闪退。");

        assertThat(questions).isEmpty();
    }

    @Test
    void handlesBlankMessageConservatively() {
        assertThat(service.suggestFollowUps(null)).hasSize(4);
        assertThat(service.suggestFollowUps(" ")).hasSize(4);
    }
}
