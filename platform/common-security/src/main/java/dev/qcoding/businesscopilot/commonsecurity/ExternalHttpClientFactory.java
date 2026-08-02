package dev.qcoding.businesscopilot.commonsecurity;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** 创建禁用重定向并带超时、同源复核和响应字节上限的企业 RestClient。 */
public class ExternalHttpClientFactory {

    private final ExternalEndpointPolicy endpointPolicy;

    public ExternalHttpClientFactory(ExternalEndpointPolicy endpointPolicy) {
        this.endpointPolicy = endpointPolicy;
    }

    public RestClient.Builder builder(String baseUrl) {
        URI base = endpointPolicy.validateBaseUrl(baseUrl);
        var settings = endpointPolicy.properties();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(shorter(settings.readTimeout(), settings.taskTimeout()));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(new SecurityInterceptor(base, endpointPolicy, settings.maxResponseBytes()));
    }

    public void ensureWithinTaskTimeout(long startedNano) {
        long elapsed = System.nanoTime() - startedNano;
        if (elapsed > endpointPolicy.properties().taskTimeout().toNanos()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部连接任务超过整体超时限制。");
        }
    }

    private Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    /** 在业务代码读取字段前统一限制 JSON 嵌套深度和数组条目总数。 */
    public JsonNode validatePayload(JsonNode payload) {
        if (payload == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部连接返回了空响应。");
        }
        PayloadCounter counter = new PayloadCounter(endpointPolicy.properties().maxItems());
        validateNode(payload, 1, endpointPolicy.properties().maxJsonDepth(), counter);
        return payload;
    }

    private void validateNode(JsonNode node, int depth, int maxDepth, PayloadCounter counter) {
        if (depth > maxDepth) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部连接 JSON 超过安全深度限制。");
        }
        if (node.isArray()) {
            counter.add(node.size());
            for (JsonNode child : node) validateNode(child, depth + 1, maxDepth, counter);
        } else if (node.isObject()) {
            node.properties().forEach(entry ->
                    validateNode(entry.getValue(), depth + 1, maxDepth, counter));
        }
    }

    private static final class PayloadCounter {
        private final int maximum;
        private int count;

        private PayloadCounter(int maximum) {
            this.maximum = maximum;
        }

        private void add(int value) {
            count += value;
            if (count > maximum) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "外部连接返回条目超过安全数量限制。");
            }
        }
    }

    private record SecurityInterceptor(URI base, ExternalEndpointPolicy policy, long maxBytes)
            implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            policy.validateRequestUrl(base, request.getURI().toString());
            ClientHttpResponse response = execution.execute(request, body);
            long declared = response.getHeaders().getContentLength();
            if (declared > maxBytes) {
                response.close();
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部连接响应超过安全大小限制。");
            }
            if (response.getStatusCode().is3xxRedirection()) {
                String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
                response.close();
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        location == null ? "外部连接返回了禁止的重定向。"
                                : "外部连接重定向需要重新配置并验证目标域名。");
            }
            return new LimitedResponse(response, maxBytes);
        }
    }

    private record LimitedResponse(ClientHttpResponse delegate, long maxBytes) implements ClientHttpResponse {
        @Override public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public void close() { delegate.close(); }
        @Override public InputStream getBody() throws IOException {
            return new FilterInputStream(delegate.getBody()) {
                private long read;
                @Override public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0) check(1);
                    return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = super.read(bytes, offset, length);
                    if (count > 0) check(count);
                    return count;
                }
                private void check(int count) {
                    read += count;
                    if (read > maxBytes) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                "外部连接响应超过安全大小限制。");
                    }
                }
            };
        }
        @Override public org.springframework.http.HttpHeaders getHeaders() { return delegate.getHeaders(); }
    }
}
