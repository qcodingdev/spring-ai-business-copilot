package dev.qcoding.businesscopilot.knowledgecopilot.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** 同步部署时显式挂载的只读目录；拒绝符号链接和目录逃逸。 */
public class MountedDriveKnowledgeSourceAdapter implements KnowledgeSourceAdapter {

    private final long maxFileBytes;
    private final int maxItems;

    public MountedDriveKnowledgeSourceAdapter(long maxFileBytes, int maxItems) {
        this.maxFileBytes = maxFileBytes;
        this.maxItems = maxItems;
    }

    @Override
    public boolean supports(KnowledgeSourceProvider provider) {
        return provider == KnowledgeSourceProvider.MOUNTED_DRIVE;
    }

    @Override
    public SourceBatch fetch(KnowledgeSourceConnection connection, String cursor) {
        Path root = Path.of(connection.rootReference()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("企业网盘挂载目录不存在");
        }
        try (var paths = Files.walk(root)) {
            List<SourceItem> items = paths.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> supported(path.getFileName().toString()))
                    .limit(maxItems)
                    .map(path -> read(root, path))
                    .toList();
            return new SourceBatch(items, null, true);
        } catch (IOException ex) {
            throw new IllegalStateException("读取企业网盘挂载目录失败", ex);
        }
    }

    private SourceItem read(Path root, Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(root)) {
                throw new IllegalStateException("检测到企业网盘目录逃逸");
            }
            Instant modified = Files.getLastModifiedTime(normalized).toInstant();
            if (Files.size(normalized) > maxFileBytes) {
                throw new IllegalStateException("企业网盘文件超过安全大小限制");
            }
            byte[] content;
            try (var input = Files.newInputStream(normalized)) {
                content = input.readNBytes(Math.toIntExact(maxFileBytes + 1));
            }
            if (content.length > maxFileBytes) {
                throw new IllegalStateException("企业网盘文件超过安全大小限制");
            }
            return new SourceItem(root.relativize(normalized).toString(),
                    normalized.getFileName().toString(), Files.probeContentType(normalized),
                    content, String.valueOf(modified.toEpochMilli()),
                    null, modified, List.of(), false);
        } catch (IOException ex) {
            throw new IllegalStateException("读取企业网盘文件失败", ex);
        }
    }

    private boolean supported(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")
                || name.endsWith(".pdf") || name.endsWith(".docx")
                || name.endsWith(".xlsx") || name.endsWith(".html") || name.endsWith(".htm");
    }
}
