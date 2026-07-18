package dev.qcoding.businesscopilot.knowledgecopilot.document;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.documentprocessing.DocumentFormat;
import dev.qcoding.businesscopilot.documentprocessing.DocumentTextExtractor;
import dev.qcoding.businesscopilot.documentprocessing.ExtractedDocument;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ChunkingService;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJob;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJobStatus;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJobRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentUploadServiceTest {

    private KnowledgeDocumentRepository documentRepository;
    private KnowledgeChunkRepository chunkRepository;
    private ChunkingService chunkingService;
    private KnowledgeEmbeddingRepository embeddingRepository;
    private DocumentTextExtractor extractor;
    private KnowledgeIndexingService indexingService;
    private KnowledgeIndexJobRepository indexJobRepository;
    private DocumentUploadService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(KnowledgeDocumentRepository.class);
        chunkRepository = mock(KnowledgeChunkRepository.class);
        chunkingService = mock(ChunkingService.class);
        embeddingRepository = mock(KnowledgeEmbeddingRepository.class);
        extractor = mock(DocumentTextExtractor.class);
        indexingService = mock(KnowledgeIndexingService.class);
        indexJobRepository = mock(KnowledgeIndexJobRepository.class);
        CurrentActorProvider actors = () -> new CurrentActor("operator", Set.of(BusinessRole.OPERATOR));
        service = new DocumentUploadService(
                documentRepository, chunkRepository,
                new MarkdownDocumentParser(), new TextDocumentParser(),
                chunkingService, new SensitiveTextMasker(),
                new KnowledgeCopilotProperties(true, 0, 5, 0.70d, "embedding-model", 1536),
                embeddingRepository, extractor, indexingService, indexJobRepository, actors);
    }

    @Test
    void uploadPersistsCurrentVersionAndQueuesIndexing() {
        UUID logicalId = UUID.randomUUID();
        KnowledgeChunk chunk = new KnowledgeChunk(
                null, 1L, "Guide", 0, "Body", "Body", 1, null);
        when(extractor.extract(any(), any(), any()))
                .thenReturn(new ExtractedDocument(DocumentFormat.MARKDOWN, "# Guide\nBody", 12));
        when(documentRepository.nextVersion(logicalId)).thenReturn(2);
        when(documentRepository.save(any())).thenReturn(1L);
        when(chunkingService.chunk(any(), any())).thenReturn(List.of(chunk));
        when(indexingService.enqueue(1L)).thenReturn(job(10L, 1L));

        DocumentUploadResponse response = service.upload(
                new DocumentUploadRequest("guide.md", "# Guide\nBody", null, logicalId));

        ArgumentCaptor<KnowledgeDocument> document = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documentRepository).supersedeCurrent(logicalId);
        verify(documentRepository).save(document.capture());
        assertThat(document.getValue().versionNo()).isEqualTo(2);
        assertThat(document.getValue().ownerActorId()).isEqualTo("operator");
        assertThat(response.indexJobId()).isEqualTo(10L);
        assertThat(response.indexed()).isFalse();
    }

    @Test
    void reindexQueuesJobForOwnedDocument() {
        KnowledgeDocument document = new KnowledgeDocument(
                1L, "Guide", "upload", "guide.md", null, "hash", false, null, null,
                UUID.randomUUID(), 1, true, "FAILED", null, "text/markdown", "operator");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocumentId(1L)).thenReturn(List.of(
                new KnowledgeChunk(11L, 1L, "Guide", 0, "Body", "Body", 1, null)));
        when(indexingService.enqueue(1L)).thenReturn(job(10L, 1L));

        KnowledgeIndexJob result = service.reindex(1L);

        assertThat(result.id()).isEqualTo(10L);
    }

    @Test
    void enablingUnindexedDocumentIsRejected() {
        KnowledgeDocument document = new KnowledgeDocument(
                1L, "Guide", "upload", "guide.md", null, "hash", false, null, null,
                UUID.randomUUID(), 1, true, "PENDING", null, "text/markdown", "operator");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(embeddingRepository.existsByDocumentId(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.updateEnabled(1L, true))
                .hasMessageContaining("已完成索引");
        verify(documentRepository, never()).updateEnabled(1L, true);
    }

    @Test
    void indexJobFromAnotherOwnerIsHidden() {
        KnowledgeDocument document = new KnowledgeDocument(
                1L, "Guide", "upload", "guide.md", null, "hash", false, null, null,
                UUID.randomUUID(), 1, true, "PENDING", null, "text/markdown", "other-operator");
        when(indexJobRepository.findById(10L)).thenReturn(Optional.of(job(10L, 1L)));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.indexJob(10L))
                .hasMessageContaining("资源不存在");
    }

    @Test
    void deletingCurrentVersionPromotesLatestPreviousVersion() {
        UUID logicalId = UUID.randomUUID();
        KnowledgeDocument document = new KnowledgeDocument(
                2L, "Guide", "upload", "guide.md", null, "hash", false, null, null,
                logicalId, 2, true, "INDEXED", null, "text/markdown", "operator");
        when(documentRepository.findById(2L)).thenReturn(Optional.of(document));
        when(documentRepository.deleteById(2L, "operator")).thenReturn(true);

        assertThat(service.delete(2L)).isTrue();

        verify(documentRepository).promoteLatestVersion(logicalId);
    }

    private KnowledgeIndexJob job(Long id, Long documentId) {
        return new KnowledgeIndexJob(id, documentId, KnowledgeIndexJobStatus.PENDING,
                0, null, null, null, null, null, null, null, null, null);
    }
}
