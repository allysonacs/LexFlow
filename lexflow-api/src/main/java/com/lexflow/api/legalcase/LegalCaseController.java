package com.lexflow.api.legalcase;

import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.legalcase.FindLegalCaseService;
import com.lexflow.application.legalcase.ReceiveLegalCaseCommand;
import com.lexflow.application.legalcase.ReceiveLegalCaseResult;
import com.lexflow.application.legalcase.ReceiveLegalCaseService;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * <p>A requisição nunca dispara classificação ou IA: a ingestão apenas grava e publica o evento, e o
 * processamento acontece depois, fora do ciclo da requisição.
 */
@RestController
@RequestMapping(LegalCaseController.BASE_PATH)
public class LegalCaseController {

    /** Caminho base do recurso; usado também para montar a URL de consulta devolvida na criação. */
    public static final String BASE_PATH = "/api/v1/legal-cases";

    /** Cabeçalho de idempotência, no nome consagrado pelo mercado. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ReceiveLegalCaseService receiveLegalCaseService;
    private final FindLegalCaseService findLegalCaseService;

    public LegalCaseController(
            ReceiveLegalCaseService receiveLegalCaseService, FindLegalCaseService findLegalCaseService) {
        this.receiveLegalCaseService = receiveLegalCaseService;
        this.findLegalCaseService = findLegalCaseService;
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
     * @param files um ou mais arquivos nos formatos aceitos
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateLegalCaseResponse> create(
            @RequestParam("caseType") String caseType,
            @RequestParam("requester") String requester,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "externalReference", required = false) String externalReference,
            @RequestParam("files") List<MultipartFile> files,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {

        ReceiveLegalCaseCommand command = new ReceiveLegalCaseCommand(
                externalReference,
                LegalCaseType.of(caseType),
                requester,
                priority == null || priority.isBlank() ? null : CasePriority.of(priority),
                toUploads(files),
                idempotencyKey);

        ReceiveLegalCaseResult result = receiveLegalCaseService.receive(command);
        URI statusUri = URI.create("%s/%s".formatted(BASE_PATH, result.legalCase().legalCase().id()));

        return ResponseEntity.created(statusUri)
                .body(CreateLegalCaseResponse.from(
                        result.legalCase().legalCase(),
                        result.legalCase().documents().size(),
                        statusUri.toString()));
    }

    /** Situação atual e metadados básicos de uma demanda. */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public LegalCaseDetailResponse findById(@PathVariable("id") UUID id) {
        return LegalCaseDetailResponse.from(findLegalCaseService.findById(id));
    }

    /**
     * Converte os arquivos do multipart em uploads independentes do Spring.
     *
     * <p>O nome original passa por {@link MultipartFile#getOriginalFilename()} e é tratado como dado
     * não confiável pelas camadas de dentro: é ele que decide o formato aceito, mas não compõe o
     * caminho no storage.
     */
    private List<DocumentUpload> toUploads(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("é obrigatório enviar ao menos um arquivo em 'files'");
        }
        return files.stream()
                .map(file -> new DocumentUpload(file.getOriginalFilename(), file.getContentType(), readBytes(file)))
                .toList();
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
