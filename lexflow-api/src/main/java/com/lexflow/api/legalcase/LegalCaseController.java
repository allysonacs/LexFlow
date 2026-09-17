package com.lexflow.api.legalcase;

import com.lexflow.api.audit.AuditLogEntryResponse;
import com.lexflow.api.common.PageResponse;
import com.lexflow.api.review.DecisionRequest;
import com.lexflow.api.review.DecisionResponse;
import com.lexflow.api.review.LegalCaseAnalysisResponse;
import com.lexflow.api.review.LegalCaseSummaryResponse;
import com.lexflow.application.checklist.DocumentChecklistService;
import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.legalcase.FindLegalCaseService;
import com.lexflow.application.legalcase.ReceiveLegalCaseCommand;
import com.lexflow.application.legalcase.ReceiveLegalCaseResult;
import com.lexflow.application.legalcase.ReceiveLegalCaseService;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.review.FindLegalCaseAnalysisService;
import com.lexflow.application.review.RegisterDecisionCommand;
import com.lexflow.application.review.RegisterDecisionResult;
import com.lexflow.application.review.RegisterDecisionService;
import com.lexflow.application.review.ResubmitDocumentationService;
import com.lexflow.domain.decision.DecisionType;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Entrada REST das demandas jurídicas (Prompt 05).
 *
 * <p>O controller faz três coisas e nada além disso: converte o que chegou pelo protocolo HTTP em um
 * comando com tipos do domínio, chama o caso de uso e monta a resposta. Nenhuma regra de negócio
 * mora aqui — nem a lista de formatos aceitos, nem o padrão de prioridade, nem a decisão sobre
 * idempotência.
 *
 * <p>A requisição nunca dispara classificação, extração ou IA: a ingestão apenas grava e publica o evento, e o
 * processamento acontece depois, fora do ciclo da requisição.
 *
 * <p>A revisão humana (Prompt 15) entra por aqui também: a fila de casos aguardando decisão, a
 * análise de um caso com as fontes citadas, o registro da decisão e o reenvio de documentação.
 *
 * <p><strong>Limitação conhecida.</strong> Enquanto não há provedor de identidade, quem decide é
 * identificado pelo cabeçalho {@code X-User-Id}. Isso identifica, mas não autentica: qualquer cliente
 * pode informar qualquer valor. Trocar por um usuário autenticado é uma mudança contida neste
 * controller e na {@code SecurityConfiguration}.
 */
@RestController
@RequestMapping(LegalCaseController.BASE_PATH)
public class LegalCaseController {

    /** Caminho base do recurso; usado também para montar a URL de consulta devolvida na criação. */
    public static final String BASE_PATH = "/api/v1/legal-cases";

    /** Cabeçalho de idempotência, no nome consagrado pelo mercado. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /**
     * Cabeçalho que identifica quem está decidindo.
     *
     * <p>Solução provisória, declarada como tal no Prompt 15: ele identifica, mas não autentica.
     */
    public static final String USER_ID_HEADER = "X-User-Id";

    private final ReceiveLegalCaseService receiveLegalCaseService;
    private final FindLegalCaseService findLegalCaseService;
    private final DocumentChecklistService checklistService;
    private final FindLegalCaseAnalysisService analysisService;
    private final RegisterDecisionService registerDecisionService;
    private final ResubmitDocumentationService resubmitDocumentationService;

    public LegalCaseController(
            ReceiveLegalCaseService receiveLegalCaseService,
            FindLegalCaseService findLegalCaseService,
            DocumentChecklistService checklistService,
            FindLegalCaseAnalysisService analysisService,
            RegisterDecisionService registerDecisionService,
            ResubmitDocumentationService resubmitDocumentationService) {
        this.receiveLegalCaseService = receiveLegalCaseService;
        this.findLegalCaseService = findLegalCaseService;
        this.checklistService = checklistService;
        this.analysisService = analysisService;
        this.registerDecisionService = registerDecisionService;
        this.resubmitDocumentationService = resubmitDocumentationService;
    }

    /**
     * Recebe uma nova demanda com os seus arquivos.
     *
     * <p>Responde {@code 201 Created} tanto na criação quanto no reenvio com a mesma
     * {@code Idempotency-Key} — no segundo caso nada é criado e o que volta é a demanda original,
     * exatamente como manda o item 3 do Prompt 05.
     *
     * @param caseType valor de {@code LegalCaseType}; um valor desconhecido resulta em 400
     * @param priority opcional; ausente significa {@link CasePriority#DEFAULT}
     * @param description opcional; texto livre que ajuda a classificação do tipo (Prompt 08)
     * @param files um ou mais arquivos nos formatos aceitos
     * @param documentTypes opcional; tipo de cada arquivo para o checklist (ex.: {@code CONTRACT_DRAFT}),
     *     na mesma ordem de {@code files}. Quando enviado, precisa ter um valor por arquivo; um valor em
     *     branco significa "sem tipo"
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateLegalCaseResponse> create(
            @RequestParam("caseType") String caseType,
            @RequestParam("requester") String requester,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "externalReference", required = false) String externalReference,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "documentTypes", required = false) List<String> documentTypes,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {

        ReceiveLegalCaseCommand command = new ReceiveLegalCaseCommand(
                externalReference,
                LegalCaseType.of(caseType),
                requester,
                description,
                priority == null || priority.isBlank() ? null : CasePriority.of(priority),
                toUploads(files, documentTypes),
                idempotencyKey);

        ReceiveLegalCaseResult result = receiveLegalCaseService.receive(command);
        URI statusUri = URI.create("%s/%s".formatted(BASE_PATH, result.legalCase().legalCase().id()));

        return ResponseEntity.created(statusUri)
                .body(CreateLegalCaseResponse.from(
                        result.legalCase().legalCase(),
                        result.legalCase().documents().size(),
                        statusUri.toString()));
    }

    /**
     * Fila de demandas, paginada (Prompt 15, item 1).
     *
     * @param status filtro opcional; a fila de revisão humana usa {@code PENDING_HUMAN_REVIEW}
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public PageResponse<LegalCaseSummaryResponse> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + PageQuery.DEFAULT_SIZE) int size) {
        LegalCaseStatus filter = status == null || status.isBlank() ? null : LegalCaseStatus.of(status);
        return PageResponse.from(
                analysisService.list(filter, PageQuery.of(page, size)), LegalCaseSummaryResponse::from);
    }

    /**
     * Respostas da IA de uma demanda, com o texto completo das fontes citadas (Prompt 15, item 2).
     *
     * <p>É o que sustenta o princípio da seção 1: a pessoa vê pergunta, resposta, confiança e fonte —
     * nunca apenas a conclusão.
     */
    @GetMapping(path = "/{id}/analysis", produces = MediaType.APPLICATION_JSON_VALUE)
    public LegalCaseAnalysisResponse findAnalysis(@PathVariable("id") UUID id) {
        return LegalCaseAnalysisResponse.from(analysisService.findByLegalCaseId(id));
    }

    /**
     * Registra a decisão de um responsável humano (Prompt 15, item 3).
     *
     * <p>Responde {@code 201 Created} tanto no registro quanto no reenvio com a mesma
     * {@code Idempotency-Key} — no segundo caso nada é criado e o que volta é a decisão original.
     *
     * @param userId cabeçalho {@code X-User-Id}; o corpo pode trazer {@code decidedBy}, e um dos dois
     *     é obrigatório
     */
    @PostMapping(
            path = "/{id}/decisions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DecisionResponse> registerDecision(
            @PathVariable("id") UUID id,
            @RequestBody DecisionRequest request,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {

        RegisterDecisionResult result = registerDecisionService.register(new RegisterDecisionCommand(
                id,
                DecisionType.valueOf(requireDecisionType(request)),
                request.decidedBy() != null && !request.decidedBy().isBlank() ? request.decidedBy() : userId,
                request.comments(),
                idempotencyKey));

        return ResponseEntity.created(URI.create("%s/%s/decisions/%s".formatted(BASE_PATH, id, result.decision().id())))
                .body(DecisionResponse.from(result.decision()));
    }

    /**
     * Reenvia a documentação de uma demanda devolvida para correção, devolvendo-a ao início do
     * pipeline (Prompt 15, item 4).
     */
    @PostMapping(
            path = "/{id}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public LegalCaseDetailResponse resubmitDocumentation(
            @PathVariable("id") UUID id,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "documentTypes", required = false) List<String> documentTypes,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return LegalCaseDetailResponse.from(
                resubmitDocumentationService.resubmit(id, toUploads(files, documentTypes), userId));
    }

    /**
     * Linha do tempo completa de uma demanda (Prompt 16, item 4).
     *
     * <p>Reconstrói a história de ponta a ponta — ingestão, cada transição, cada resposta da IA e cada
     * decisão — a partir de uma tabela que o banco não deixa alterar nem apagar.
     */
    @GetMapping(path = "/{id}/audit-log", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AuditLogEntryResponse> findAuditLog(@PathVariable("id") UUID id) {
        return analysisService.auditTrail(id).stream().map(AuditLogEntryResponse::from).toList();
    }

    /** Situação atual e metadados básicos de uma demanda. */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public LegalCaseDetailResponse findById(@PathVariable("id") UUID id) {
        return LegalCaseDetailResponse.from(findLegalCaseService.findById(id));
    }

    /**
     * Checklist documental da demanda e a resposta determinística a
     * {@code HAS_SUFFICIENT_DOCUMENTATION} (Prompt 09).
     */
    @GetMapping(path = "/{id}/checklist", produces = MediaType.APPLICATION_JSON_VALUE)
    public LegalCaseChecklistResponse findChecklist(@PathVariable("id") UUID id) {
        return LegalCaseChecklistResponse.from(checklistService.findByLegalCaseId(id));
    }

    /**
     * Converte os arquivos do multipart em uploads independentes do Spring.
     *
     * <p>O nome original passa por {@link MultipartFile#getOriginalFilename()} e é tratado como dado
     * não confiável pelas camadas de dentro: é ele que decide o formato aceito, mas não compõe o
     * caminho no storage.
     */
    private List<DocumentUpload> toUploads(List<MultipartFile> files, List<String> documentTypes) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("é obrigatório enviar ao menos um arquivo em 'files'");
        }
        if (documentTypes != null && !documentTypes.isEmpty() && documentTypes.size() != files.size()) {
            throw new IllegalArgumentException(
                    "'documentTypes' deve ter um valor para cada arquivo: %d arquivo(s) e %d tipo(s)"
                            .formatted(files.size(), documentTypes.size()));
        }
        List<DocumentUpload> uploads = new ArrayList<>(files.size());
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            String documentType = documentTypes == null || documentTypes.isEmpty() ? null : documentTypes.get(index);
            uploads.add(new DocumentUpload(
                    file.getOriginalFilename(), file.getContentType(), readBytes(file), documentType));
        }
        return uploads;
    }

    /** O tipo de decisão é obrigatório; um valor desconhecido vira 400 pelo tratamento de erros. */
    private static String requireDecisionType(DecisionRequest request) {
        if (request == null || request.decisionType() == null || request.decisionType().isBlank()) {
            throw new IllegalArgumentException("decisionType é obrigatório: APPROVED, REJECTED ou RETURNED_FOR_CORRECTION");
        }
        return request.decisionType().trim().toUpperCase(java.util.Locale.ROOT);
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
