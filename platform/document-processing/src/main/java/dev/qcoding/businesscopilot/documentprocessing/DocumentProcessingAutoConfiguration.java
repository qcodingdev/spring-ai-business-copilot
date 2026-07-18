package dev.qcoding.businesscopilot.documentprocessing;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 自动配置共享的受限文档文本提取器。 */
@AutoConfiguration
@EnableConfigurationProperties(DocumentExtractionProperties.class)
public class DocumentProcessingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DocumentTextExtractor documentTextExtractor(DocumentExtractionProperties properties) {
        return new BoundedDocumentTextExtractor(properties);
    }
}
