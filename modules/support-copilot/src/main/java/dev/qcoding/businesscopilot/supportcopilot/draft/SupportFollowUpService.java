package dev.qcoding.businesscopilot.supportcopilot.draft;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SUP-01：缺失信息的确定性追问建议。
 *
 * <p>根据工单内容识别解决问题必需但缺失的要素（订单/工单号、发生时间、报错信息、
 * 操作步骤、账号信息），生成待人工审核的追问建议。建议只写入草稿供人工确认，
 * 绝不自动发送给客户。</p>
 */
@Component
public class SupportFollowUpService {

    private static final Pattern ORDER_REF = Pattern.compile("(订单|工单|单号|order)\\s*[:：#号]?\\s*\\w{4,}|\\d{6,}");
    private static final Pattern TIME_ANCHOR = Pattern.compile(
            "\\d{4}[-/年]\\d{1,2}|\\d{1,2}月\\d{1,2}|今?昨?天|上午|下午|刚刚|小时前| days? ago");
    private static final Pattern ERROR_TEXT = Pattern.compile("报错|错误|失败|异常|error|Error|无法|不能|闪退|超时");
    private static final Pattern OPERATION_STEPS = Pattern.compile("步骤|操作|点击|如何|怎么|流程|登录|下单|支付");

    /** 识别缺失要素并给出追问建议；全部命中时返回空列表。 */
    public List<String> suggestFollowUps(String maskedMessage) {
        List<String> questions = new ArrayList<>();
        String message = maskedMessage == null ? "" : maskedMessage;
        if (!ORDER_REF.matcher(message).find()) {
            questions.add("请提供相关的订单号或工单号，便于定位记录。");
        }
        if (!TIME_ANCHOR.matcher(message).find()) {
            questions.add("请说明问题发生的大致时间。");
        }
        if (!ERROR_TEXT.matcher(message).find()) {
            questions.add("请描述具体报错或异常表现（如截图中的错误提示）。");
        }
        if (!OPERATION_STEPS.matcher(message).find()) {
            questions.add("请说明出现问题前的操作步骤。");
        }
        return questions;
    }
}
