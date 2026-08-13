package dev.qcoding.businesscopilot.commonweb.request;

/**
 * Thread-bound request context used by API envelopes and audit repositories.
 * Background work without an HTTP request is attributed to {@code system}.
 */
public final class BusinessRequestContextHolder {

    private static final ThreadLocal<BusinessRequestContext> CONTEXT = new ThreadLocal<>();

    private BusinessRequestContextHolder() {
    }

    public static void set(BusinessRequestContext context) {
        CONTEXT.set(context);
    }

    public static BusinessRequestContext current() {
        return CONTEXT.get();
    }

    public static String currentRequestId() {
        BusinessRequestContext context = CONTEXT.get();
        return context != null ? context.requestId() : null;
    }

    public static String currentActorId() {
        BusinessRequestContext context = CONTEXT.get();
        return context != null ? context.actorId() : "system";
    }

    /** 只返回固定低基数值；未处于 HTTP 请求时保持产品默认中文。 */
    public static String currentLocale() {
        BusinessRequestContext context = CONTEXT.get();
        return context != null ? context.locale() : "zh-CN";
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
