package dev.qcoding.businesscopilot;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 对登录用户提供可下载、可再次上传的虚构样例文件。
 *
 * <p>样例文件不自动写入业务表，用户可以在界面中先下载、检查，再通过对应模块上传，
 * 从而完整体验文件解析、校验、索引和人工确认链路。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SampleResourceConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/samples/knowledge/**")
                .addResourceLocations("classpath:/sample-knowledge/")
                .setCachePeriod((int) Duration.ofHours(1).toSeconds());
        registry.addResourceHandler("/samples/report/**")
                .addResourceLocations("classpath:/sample-report/")
                .setCachePeriod((int) Duration.ofHours(1).toSeconds());
        registry.addResourceHandler("/samples/resume/**")
                .addResourceLocations("classpath:/sample-resume/")
                .setCachePeriod((int) Duration.ofHours(1).toSeconds());
    }
}
