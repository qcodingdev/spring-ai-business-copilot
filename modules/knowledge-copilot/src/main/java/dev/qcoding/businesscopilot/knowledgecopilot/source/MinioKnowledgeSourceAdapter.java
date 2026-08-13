package dev.qcoding.businesscopilot.knowledgecopilot.source;

import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;

/** S3/MinIO 只读对象同步；凭证由环境变量 JSON 提供。 */
public class MinioKnowledgeSourceAdapter implements KnowledgeSourceAdapter {

    private final ExternalSecretResolver secretResolver;
    private final ObjectMapper objectMapper;
    private final ExternalEndpointPolicy endpointPolicy;

    public MinioKnowledgeSourceAdapter(ExternalSecretResolver secretResolver, ObjectMapper objectMapper,
                                       ExternalEndpointPolicy endpointPolicy) {
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
        this.endpointPolicy = endpointPolicy;
    }

    @Override
    public boolean supports(KnowledgeSourceProvider provider) {
        return provider == KnowledgeSourceProvider.S3 || provider == KnowledgeSourceProvider.MINIO;
    }

    @Override
    public SourceBatch fetch(KnowledgeSourceConnection connection, String cursor) {
        Credentials credentials = credentials(connection.secretRef());
        endpointPolicy.validateBaseUrl(connection.baseUrl());
        String[] root = connection.rootReference().split("/", 2);
        String bucket = root[0];
        String prefix = root.length == 2 ? root[1] : "";
        var limits = endpointPolicy.properties();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(limits.connectTimeout())
                .readTimeout(limits.readTimeout())
                .callTimeout(limits.taskTimeout())
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(chain -> {
                    endpointPolicy.validateRequestUrl(
                            endpointPolicy.validateBaseUrl(connection.baseUrl()),
                            chain.request().url().toString());
                    return chain.proceed(chain.request());
                })
                .build();
        MinioClient client = MinioClient.builder()
                .endpoint(connection.baseUrl())
                .credentials(credentials.accessKey(), credentials.secretKey())
                .httpClient(httpClient)
                .build();
        List<SourceItem> items = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build());
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.isDir()) continue;
                if (item.size() > limits.maxResponseBytes()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "S3/MinIO 对象超过安全大小限制");
                }
                try (InputStream input = client.getObject(GetObjectArgs.builder()
                        .bucket(bucket).object(item.objectName()).build())) {
                    byte[] content = input.readNBytes(Math.toIntExact(limits.maxResponseBytes() + 1));
                    if (content.length > limits.maxResponseBytes()) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                "S3/MinIO 对象超过安全大小限制");
                    }
                    items.add(new SourceItem(item.objectName(), fileName(item.objectName()),
                            null, content, item.etag(), item.etag(),
                            item.lastModified() == null ? Instant.now() : item.lastModified().toInstant(),
                            List.of(), false));
                }
                if (items.size() >= limits.maxItems()) break;
            }
            return new SourceBatch(items, null, true);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 S3/MinIO 企业资料失败", ex);
        }
    }

    private Credentials credentials(String secretRef) {
        try {
            return objectMapper.readValue(secretResolver.resolve(secretRef), Credentials.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("S3/MinIO 凭证必须是 accessKey/secretKey JSON", ex);
        }
    }

    private String fileName(String objectName) {
        int slash = objectName.lastIndexOf('/');
        return slash >= 0 ? objectName.substring(slash + 1) : objectName;
    }

    public record Credentials(String accessKey, String secretKey) { }
}
