package dev.qcoding.businesscopilot.commonsecurity;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.springframework.core.env.Environment;

/** 默认只从运行环境读取外部凭证，不从请求或数据库读取密钥正文。 */
public class EnvironmentExternalSecretResolver implements ExternalSecretResolver {

    private final Environment environment;

    public EnvironmentExternalSecretResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String resolve(String secretRef) {
        String ref = ExternalSecretResolver.validateRef(secretRef);
        String value = environment.getProperty(ref);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "外部系统凭证尚未在运行环境配置");
        }
        return value;
    }
}
