package com.lexflow.api.knowledge;

import com.lexflow.api.common.PageResponse;
import com.lexflow.application.knowledge.IngestKnowledgeBaseFileCommand;
import com.lexflow.application.knowledge.IngestKnowledgeBaseSourceCommand;
import com.lexflow.application.knowledge.IngestKnowledgeBaseSourceService;
import com.lexflow.application.knowledge.KnowledgeBaseIngestionResult;
import com.lexflow.application.knowledge.KnowledgeBaseRetriever;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.domain.knowledge.KnowledgeBaseSourceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Administração da base normativa usada no RAG (Prompt 12, item 1).
 *
 * <p>Restrito ao papel {@code ADMIN} pela {@link com.lexflow.api.security.SecurityConfiguration}:
 * indexar uma norma muda o fundamento de toda resposta futura da IA, e remover uma fonte é tão
 * sensível quanto alterar uma regra de checklist.
 *
 * <p>A fonte pode chegar de duas formas, e o resultado é o mesmo: como texto, em JSON, ou como
 * arquivo, que passa pelo mesmo extrator dos documentos das demandas (Tika, com OCR quando a norma
 * vem digitalizada).
 *
 * <p>A consulta de recuperação existe para conferência: ela mostra o que a busca vetorial devolveria
 * para uma pergunta, sem chamar o LLM e sem emitir opinião jurídica nenhuma.
 */
@RestController
@RequestMapping(path = KnowledgeBaseController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class KnowledgeBaseController {

    public static final String BASE_PATH = "/api/v1/knowledge-base";

    private static final String SOURCES_PATH = "/sources";

    private final IngestKnowledgeBaseSourceService ingestionService;
    private final KnowledgeBaseRetriever retriever;

    public KnowledgeBaseController(
            IngestKnowledgeBaseSourceService ingestionService, KnowledgeBaseRetriever retriever) {
        this.ingestionService = ingestionService;
        this.retriever = retriever;
    }

    /** Indexa uma fonte normativa enviada como texto. */
    @PostMapping(path = SOURCES_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KnowledgeBaseSourceResponse> createFromText(
            @RequestBody KnowledgeBaseSourceRequest request) {
        KnowledgeBaseIngestionResult result = ingestionService.ingest(new IngestKnowledgeBaseSourceCommand(
                request.title(),
                KnowledgeBaseSourceType.of(request.sourceType()),
                request.effectiveDate(),
                request.text()));
        return created(result);
    }

    /** Indexa uma fonte normativa enviada como arquivo (PDF, DOCX, imagem, {@code .txt} ou {@code .md}). */
    @PostMapping(path = SOURCES_PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeBaseSourceResponse> createFromFile(
            @RequestParam("title") String title,
            @RequestParam("sourceType") String sourceType,
            @RequestParam(value = "effectiveDate", required = false) LocalDate effectiveDate,
            @RequestParam("file") MultipartFile file) {
        KnowledgeBaseIngestionResult result = ingestionService.ingest(new IngestKnowledgeBaseFileCommand(
                title,
                KnowledgeBaseSourceType.of(sourceType),
                effectiveDate,
                file.getOriginalFilename(),
                file.getContentType(),
                readBytes(file)));
        return created(result);
    }

    /** Fontes cadastradas, paginadas e ordenadas por título. */
    @GetMapping(SOURCES_PATH)
    public PageResponse<KnowledgeBaseSourceResponse> listSources(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + PageQuery.DEFAULT_SIZE) int size) {
        return PageResponse.from(ingestionService.list(PageQuery.of(page, size)), KnowledgeBaseSourceResponse::from);
    }

    /** Uma fonte e os seus trechos, na ordem do documento. */
    @GetMapping(SOURCES_PATH + "/{id}")
    public KnowledgeBaseSourceDetailResponse findSource(@PathVariable("id") UUID id) {
        return KnowledgeBaseSourceDetailResponse.from(ingestionService.findById(id));
    }

    /**
     * Mostra os trechos que a busca vetorial devolveria para uma consulta.
     *
     * <p>É uma ferramenta de conferência da base, não uma resposta jurídica: nenhum LLM é chamado.
     *
     * @param limit quantidade máxima de trechos; ausente usa o padrão configurado
     */
    @GetMapping("/search")
    public List<RetrievedChunkResponse> search(
            @RequestParam("query") String query, @RequestParam(value = "limit", required = false) Integer limit) {
        List<com.lexflow.application.knowledge.RetrievedChunk> chunks =
                limit == null ? retriever.retrieve(query) : retriever.retrieve(query, limit);
        return chunks.stream().map(RetrievedChunkResponse::from).toList();
    }

    private ResponseEntity<KnowledgeBaseSourceResponse> created(KnowledgeBaseIngestionResult result) {
        return ResponseEntity.created(URI.create(BASE_PATH + SOURCES_PATH + "/" + result.source().id()))
                .body(KnowledgeBaseSourceResponse.from(result));
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            // Falha de leitura do corpo da requisição é problema de transporte, não de negócio.
            throw new UncheckedIOException("Não foi possível ler o arquivo enviado", e);
        }
    }
}
