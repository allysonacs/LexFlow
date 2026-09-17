package com.lexflow.application.checklist;

import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.exception.ChecklistRuleNotFoundException;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Administração das regras de checklist (Prompt 09, item 1).
 *
 * <p>As regras são <strong>configuração de negócio</strong>, não código: quem decide quais documentos
 * cada tipo de demanda exige é a área jurídica, e a mudança vale sem novo deploy. As regras iniciais
 * vêm de uma migration (seed), apenas como ponto de partida.
 *
 * <p>Duas restrições protegem o histórico das demandas já avaliadas:
 *
 * <ul>
 *   <li>o tipo de demanda de uma regra nunca muda — uma regra de outro tipo é outra regra;
 *   <li>uma regra em uso não pode ser excluída nem trocar o documento exigido. Descrição e
 *       obrigatoriedade podem mudar a qualquer momento, e a obrigatoriedade passa a valer na
 *       próxima consulta de suficiência.
 * </ul>
 */
public class ManageChecklistRulesService {

    private final ChecklistRuleRepository ruleRepository;
    private final DocumentChecklistItemRepository itemRepository;
    private final TransactionRunner transactionRunner;
    private final Supplier<UUID> idGenerator;

    public ManageChecklistRulesService(
            ChecklistRuleRepository ruleRepository,
            DocumentChecklistItemRepository itemRepository,
            TransactionRunner transactionRunner,
            Supplier<UUID> idGenerator) {
        this.ruleRepository = Objects.requireNonNull(ruleRepository, "ruleRepository não pode ser nulo");
        this.itemRepository = Objects.requireNonNull(itemRepository, "itemRepository não pode ser nulo");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
    }

    /**
     * Cria uma regra.
     *
     * @throws DuplicateChecklistRuleException se o tipo de demanda já exigir o mesmo documento
     */
    public ChecklistRule create(LegalCaseType caseType, ChecklistRuleCommand command) {
        Objects.requireNonNull(caseType, "caseType é obrigatório");
        Objects.requireNonNull(command, "command não pode ser nulo");
        ChecklistRule rule = new ChecklistRule(
                idGenerator.get(), caseType, command.requiredDocumentType(), command.description(), command.mandatory());
        return transactionRunner.inTransaction(() -> {
            requireUnique(rule);
            return ruleRepository.save(rule);
        });
    }

    /**
     * Altera os campos editáveis de uma regra.
     *
     * @throws ChecklistRuleNotFoundException se a regra não existir
     * @throws ChecklistRuleInUseException se a regra estiver em uso e o documento exigido mudar
     * @throws DuplicateChecklistRuleException se o novo documento já for exigido por outra regra do tipo
     */
    public ChecklistRule update(UUID id, ChecklistRuleCommand command) {
        Objects.requireNonNull(command, "command não pode ser nulo");
        return transactionRunner.inTransaction(() -> {
            ChecklistRule current = findById(id);
            ChecklistRule updated =
                    current.withChanges(command.requiredDocumentType(), command.description(), command.mandatory());
            if (!updated.requiredDocumentType().equals(current.requiredDocumentType())) {
                if (itemRepository.existsByChecklistRuleId(id)) {
                    throw new ChecklistRuleInUseException(id);
                }
                requireUnique(updated);
            }
            return ruleRepository.save(updated);
        });
    }

    /**
     * Exclui uma regra que nenhuma demanda usa.
     *
     * @throws ChecklistRuleNotFoundException se a regra não existir
     * @throws ChecklistRuleInUseException se a regra já tiver gerado itens de checklist
     */
    public void delete(UUID id) {
        transactionRunner.runInTransaction(() -> {
            findById(id);
            if (itemRepository.existsByChecklistRuleId(id)) {
                throw new ChecklistRuleInUseException(id);
            }
            ruleRepository.deleteById(id);
        });
    }

    /** @throws ChecklistRuleNotFoundException se a regra não existir */
    public ChecklistRule findById(UUID id) {
        Objects.requireNonNull(id, "id não pode ser nulo");
        return ruleRepository.findById(id).orElseThrow(() -> new ChecklistRuleNotFoundException(id));
    }

    /** @param caseType filtro opcional */
    public PageResult<ChecklistRule> list(LegalCaseType caseType, PageQuery pageQuery) {
        return ruleRepository.findAll(caseType, Objects.requireNonNull(pageQuery, "pageQuery não pode ser nulo"));
    }

    private void requireUnique(ChecklistRule rule) {
        // Checagem antecipada para dar uma mensagem clara; a garantia de verdade é o índice único.
        if (ruleRepository.existsByCaseTypeAndRequiredDocumentType(rule.caseType(), rule.requiredDocumentType())) {
            throw new DuplicateChecklistRuleException(rule.caseType(), rule.requiredDocumentType());
        }
    }
}
