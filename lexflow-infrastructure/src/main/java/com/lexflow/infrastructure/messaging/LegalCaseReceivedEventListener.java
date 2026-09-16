package com.lexflow.infrastructure.messaging;

import com.lexflow.application.legalcase.LegalCaseProcessingResult;
import com.lexflow.application.legalcase.LegalCaseReceivedEvent;
import com.lexflow.application.legalcase.ProcessLegalCaseReceivedEventService;
import com.lexflow.domain.classification.LegalCaseClassification;
import com.lexflow.domain.document.TextExtractionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor da fila de demandas recebidas.
 *
 * <p>É de propósito uma casca fina: desserializa, delega ao caso de uso e registra o desfecho. Toda a
 * decisão — reservar o evento, classificar, gerar o checklist, extrair o texto, tratar a duplicidade — está em
 * {@link ProcessLegalCaseReceivedEventService}, que não conhece RabbitMQ e pode ser testado sem
 * broker nenhum.
 *
 * <p><strong>Falhar é intencional.</strong> Quando o caso de uso lança, a exceção sobe: é ela que
 * aciona o retry com backoff configurado em {@code spring.rabbitmq.listener.simple.retry} e, esgotadas
 * as tentativas, faz o broker mandar a mensagem para a dead-letter. Capturar a exceção aqui daria um
 * "processado com sucesso" falso e perderia a mensagem em silêncio.
 *
 * <p>O identificador do listener existe para que os testes possam iniciá-lo sob demanda, mantendo
 * determinístico o que de outro modo seria uma corrida com o consumo em segundo plano.
 */
@Component
public class LegalCaseReceivedEventListener {

    /** Identificador do container deste listener, usado pelos testes de integração. */
    public static final String LISTENER_ID = "legalCaseReceivedListener";

    private static final Logger log = LoggerFactory.getLogger(LegalCaseReceivedEventListener.class);

    private final ProcessLegalCaseReceivedEventService processLegalCaseReceivedEventService;

    public LegalCaseReceivedEventListener(ProcessLegalCaseReceivedEventService processLegalCaseReceivedEventService) {
        this.processLegalCaseReceivedEventService = processLegalCaseReceivedEventService;
    }

    @RabbitListener(id = LISTENER_ID, queues = RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE)
    public void onLegalCaseReceived(LegalCaseReceivedEvent event) {
        LegalCaseProcessingResult result = processLegalCaseReceivedEventService.process(event);

        if (result.isSkipped()) {
            // Entrega repetida não é erro: a seção 11 manda registrar e seguir em frente.
            log.info(
                    "Evento {} descartado ({}): demanda={} chave={}",
                    LegalCaseReceivedEvent.EVENT_TYPE,
                    result.outcome(),
                    event.legalCaseId(),
                    event.idempotencyKey());
            return;
        }

        result.classificationIfPerformed().ifPresent(classification -> logClassification(event, classification));
        result.checklistIfEvaluated().ifPresent(checklist -> log.info(
                "Checklist: demanda={} documentação suficiente={} obrigatórios faltantes={}",
                event.legalCaseId(),
                checklist.hasSufficientDocumentation(),
                checklist.missingMandatoryDocumentTypes()));

        // Só contagens: o texto dos documentos nunca vai para o log (seção 12).
        long failed = result.countByStatus(TextExtractionStatus.FAILED);
        String message = "Evento {} processado: demanda={} chave={} documentos: {} com texto, {} sem texto, {} ilegíveis";
        Object[] arguments = {
            LegalCaseReceivedEvent.EVENT_TYPE,
            event.legalCaseId(),
            event.idempotencyKey(),
            result.countByStatus(TextExtractionStatus.EXTRACTED),
            result.countByStatus(TextExtractionStatus.NO_TEXT_FOUND),
            failed
        };
        if (failed > 0) {
            log.warn(message, arguments);
        } else {
            log.info(message, arguments);
        }
    }

    private static void logClassification(LegalCaseReceivedEvent event, LegalCaseClassification classification) {
        if (classification.outcome().requiresAttention()) {
            log.warn(
                    "Classificação divergente: demanda={} tipo informado={} palavras-chave apontam {}",
                    event.legalCaseId(),
                    classification.declaredType(),
                    classification.keywords().topTypes());
            return;
        }
        log.info(
                "Classificação: demanda={} tipo={} resultado={}",
                event.legalCaseId(),
                classification.resolvedType(),
                classification.outcome());
    }
}
