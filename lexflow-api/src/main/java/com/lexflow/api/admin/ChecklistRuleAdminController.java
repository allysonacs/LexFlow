package com.lexflow.api.admin;

import com.lexflow.api.common.PageResponse;
import com.lexflow.application.checklist.ChecklistRuleCommand;
import com.lexflow.application.checklist.ManageChecklistRulesService;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD administrativo das regras de checklist (Prompt 09, item 1), restrito ao papel {@code ADMIN}
 * pela {@link com.lexflow.api.security.SecurityConfiguration}.
 *
 * <p>As regras são configuração de negócio: alterá-las muda o que as próximas demandas precisam
 * apresentar, sem novo deploy. As restrições que protegem as demandas já avaliadas ficam no caso de
 * uso ({@link ManageChecklistRulesService}).
 */
@RestController
@RequestMapping(path = ChecklistRuleAdminController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class ChecklistRuleAdminController {

    public static final String BASE_PATH = "/api/v1/admin/checklist-rules";

    private final ManageChecklistRulesService service;

    public ChecklistRuleAdminController(ManageChecklistRulesService service) {
        this.service = service;
    }

    /**
     * Lista as regras, paginadas e ordenadas por tipo de demanda e código do documento.
     *
     * @param caseType filtro opcional
     */
    @GetMapping
    public PageResponse<ChecklistRuleResponse> list(
            @RequestParam(value = "caseType", required = false) String caseType,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + PageQuery.DEFAULT_SIZE) int size) {
        LegalCaseType type = caseType == null || caseType.isBlank() ? null : LegalCaseType.of(caseType);
        return PageResponse.from(service.list(type, PageQuery.of(page, size)), ChecklistRuleResponse::from);
    }

    @GetMapping("/{id}")
    public ChecklistRuleResponse findById(@PathVariable("id") UUID id) {
        return ChecklistRuleResponse.from(service.findById(id));
    }

    /** Cria uma regra. Responde {@code 409} se o tipo de demanda já exigir o mesmo documento. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChecklistRuleResponse> create(@RequestBody ChecklistRuleRequest request) {
        ChecklistRule created = service.create(LegalCaseType.of(request.caseType()), toCommand(request));
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id()))
                .body(ChecklistRuleResponse.from(created));
    }

    /**
     * Altera documento exigido, descrição e obrigatoriedade. Um {@code caseType} diferente do atual é
     * recusado; trocar o documento de uma regra em uso responde {@code 409}.
     */
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChecklistRuleResponse update(@PathVariable("id") UUID id, @RequestBody ChecklistRuleRequest request) {
        if (request.caseType() != null && !request.caseType().isBlank()) {
            LegalCaseType informed = LegalCaseType.of(request.caseType());
            if (informed != service.findById(id).caseType()) {
                throw new IllegalArgumentException("o tipo de demanda de uma regra não pode ser alterado");
            }
        }
        return ChecklistRuleResponse.from(service.update(id, toCommand(request)));
    }

    /** Exclui uma regra sem uso. Uma regra em uso responde {@code 409}: torne-a opcional. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static ChecklistRuleCommand toCommand(ChecklistRuleRequest request) {
        return new ChecklistRuleCommand(request.requiredDocumentType(), request.description(), request.mandatory());
    }
}
