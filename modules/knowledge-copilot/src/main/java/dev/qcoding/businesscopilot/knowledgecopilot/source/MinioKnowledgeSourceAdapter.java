package dev.qcoding.businesscopilot.knowledgecopilot.source;

import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
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

/** S3/MinIO 只读对象同步；凭证由环境变量 JSON 提供。 */
public class MinioKnowledgeSourceAdapter implements KnowledgeSourceAdapter {

    private final ExternalSecretResolver secretResolver;
    private final ObjectMapper objectMapper;

    public MinioKnowledgeSourceAdapter(ExternalSecretResolver secretResolver, ObjectMapper objectMapper) {
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(KnowledgeSourceProvider provider) {
        return provider == KnowledgeSourceProvider.S3 || provider == KnowledgeSourceProvider.MINIO;
    }

    @Override
    public SourceBatch fetch(KnowledgeSourceConnection connection, String cursor) {
        Credentials credentials = credentials(connection.secretRef());
        String[] root = connection.rootReference().split("/", 2);
        String bucket = root[0];
        String prefix = root.length == 2 ? root[1] : "";
        MinioClient client = MinioClient.builder()
                .endpoint(connection.baseUrl())
                .credentials(credentials.accessKey(), credentials.secretKey())
                .build();
        List<SourceItem> items = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build());
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.isDir()) continue;
                try (InputStream input = client.getObject(GetObjectArgs.builder()
                        .bucket(bucket).object(item.objectName()).build())) {
                    items.add(new SourceItem(item.objectName(), fileName(item.objectName()),
                            null, input.readAllBytes(), item.etag(), item.etag(),
                            item.lastModified() == null ? Instant.now() : item.lastModified().toInstant(),
                            List.of(), false));
                }
                if (items.size() >= 10_000) break;
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
