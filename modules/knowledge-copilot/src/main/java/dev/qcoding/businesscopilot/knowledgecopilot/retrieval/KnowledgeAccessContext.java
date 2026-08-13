package dev.qcoding.businesscopilot.knowledgecopilot.retrieval;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;

/** 将本次检索的分类与角色范围传递给 JDBC 检索，不写入日志。 */
public final class KnowledgeAccessContext {

    private static final ThreadLocal<String> CATEGORY = new ThreadLocal<>();

    private KnowledgeAccessContext() {
    }

    public static void setCategory(String category) {
        if (category == null || category.isBlank()) CATEGORY.remove();
        else CATEGORY.set(category.trim());
    }

    public static String category() {
        return CATEGORY.get();
    }

    public static boolean reviewerAllowed() {
        BusinessRequestContext context = BusinessRequestContextHolder.current();
        return context != null && (context.roles().contains("ADMIN") || context.roles().contains("REVIEWER"));
    }

    public static boolean adminAllowed() {
        BusinessRequestContext context = BusinessRequestContextHolder.current();
        return context != null && context.roles().contains("ADMIN");
    }

    public static void clear() {
        CATEGORY.remove();
    }
}
