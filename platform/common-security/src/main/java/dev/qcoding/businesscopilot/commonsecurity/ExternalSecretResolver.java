package dev.qcoding.businesscopilot.commonsecurity;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;

/** 通过引用解析外部系统凭证；业务表只能保存引用，不能保存密钥正文。 */
@FunctionalInterface
public interface ExternalSecretResolver {

    String resolve(String secretRef);

    static String validateRef(String secretRef) {
        if (secretRef == null || !secretRef.matches("[A-Z][A-Z0-9_]{2,199}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "外部凭证引用必须是大写环境变量名");
        }
        return secretRef;
    }
}
