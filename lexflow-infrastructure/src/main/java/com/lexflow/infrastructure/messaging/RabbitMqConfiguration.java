package com.lexflow.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia e serialização da mensageria do LexFlow.
 *
 * <h2>Por que RabbitMQ, e não Kafka</h2>
 *
 * <p>A seção 8 da base de conhecimento deixou a decisão em aberto. Para o volume descrito — milhares
 * de eventos por dia, isto é, poucos por minuto, com múltiplos consumidores — os dois dão conta com
 * folga, então o critério de desempate foi simplicidade operacional, e por três razões concretas:
 *
 * <ol>
 *   <li><strong>Dead-letter é nativa.</strong> O que o Prompt 07 pede — reencaminhar automaticamente
 *       a mensagem que falhou repetidas vezes — é um argumento de fila no RabbitMQ
 *       ({@code x-dead-letter-exchange}). No Kafka seria um tópico separado mais um error handler na
 *       aplicação, com o consumidor responsável por republicar.
 *   <li><strong>O paralelismo não é limitado por partições.</strong> Uma demanda jurídica é
 *       independente das outras e a ordem entre elas é irrelevante. No RabbitMQ acrescentar
 *       consumidores é só subir mais réplicas; no Kafka o paralelismo é limitado pelo número de
 *       partições, que passa a ser uma decisão de capacidade tomada cedo demais.
 *   <li><strong>Menos peças para operar.</strong> Um broker contra um cluster com coordenação,
 *       retenção e offsets — sem nenhuma necessidade de reprocessar o histórico, que é justamente o
 *       que justificaria o log durável do Kafka.
 * </ol>
 *
 * <p>Se um dia o requisito passar a incluir reprocessar meses de eventos ou entregar o mesmo fluxo a
 * vários times independentes, essa vantagem se inverte — e a troca fica contida nesta classe e nos
 * dois adapters ao lado, já que a aplicação só conhece a porta
 * {@link com.lexflow.application.legalcase.LegalCaseReceivedEventPublisher}.
 *
 * <h2>Topologia</h2>
 *
 * <pre>
 * lexflow.events (topic) --[legal-case.received]-------------> lexflow.legal-case-received
 *                        --[legal-case.decision-registered]--> lexflow.decision-registered
 *                                                         |
 *                                      esgotadas as tentativas (x-dead-letter-exchange)
 *                                                         v
 * lexflow.events.dlx (topic) --[mesma chave]--> lexflow.&lt;fila&gt;.dlq
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class RabbitMqConfiguration {

    /** Exchange por onde passam os eventos de domínio. */
    public static final String EVENTS_EXCHANGE = "lexflow.events";

    /** Exchange das mensagens que esgotaram as tentativas. */
    public static final String DEAD_LETTER_EXCHANGE = "lexflow.events.dlx";

    /** Fila consumida quando uma demanda é recebida. */
    public static final String LEGAL_CASE_RECEIVED_QUEUE = "lexflow.legal-case-received";

    /** Dead-letter da fila acima. */
    public static final String LEGAL_CASE_RECEIVED_DLQ = LEGAL_CASE_RECEIVED_QUEUE + ".dlq";

    /** Chave de roteamento do evento de demanda recebida. */
    public static final String LEGAL_CASE_RECEIVED_ROUTING_KEY = "legal-case.received";

    /** Fila dos eventos de decisão humana (Prompt 15). */
    public static final String DECISION_REGISTERED_QUEUE = "lexflow.decision-registered";

    /** Dead-letter da fila de decisões. */
    public static final String DECISION_REGISTERED_DLQ = DECISION_REGISTERED_QUEUE + ".dlq";

    /** Chave de roteamento do evento de decisão registrada. */
    public static final String DECISION_REGISTERED_ROUTING_KEY = "legal-case.decision-registered";

    @Bean
    public TopicExchange lexflowEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange lexflowDeadLetterExchange() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * Fila principal, ligada à dead-letter.
     *
     * <p>É o {@code x-dead-letter-exchange} que faz o broker reencaminhar sozinho a mensagem rejeitada
     * sem reenfileiramento — não é preciso nenhum código de aplicação para isso.
     */
    @Bean
    public Queue legalCaseReceivedQueue() {
        return QueueBuilder.durable(LEGAL_CASE_RECEIVED_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", LEGAL_CASE_RECEIVED_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue legalCaseReceivedDeadLetterQueue() {
        return QueueBuilder.durable(LEGAL_CASE_RECEIVED_DLQ).build();
    }

    @Bean
    public Binding legalCaseReceivedBinding() {
        return BindingBuilder.bind(legalCaseReceivedQueue())
                .to(lexflowEventsExchange())
                .with(LEGAL_CASE_RECEIVED_ROUTING_KEY);
    }

    @Bean
    public Binding legalCaseReceivedDeadLetterBinding() {
        return BindingBuilder.bind(legalCaseReceivedDeadLetterQueue())
                .to(lexflowDeadLetterExchange())
                .with(LEGAL_CASE_RECEIVED_ROUTING_KEY);
    }

    /**
     * Fila das decisões humanas.
     *
     * <p>Ela é declarada agora, junto com a publicação (Prompt 15), e não junto com o primeiro
     * consumidor: sem fila ligada à exchange, o broker descartaria em silêncio todo evento publicado
     * antes de o consumidor existir — e uma decisão perdida é exatamente o que a auditoria do Prompt
     * 16 não pode admitir.
     */
    @Bean
    public Queue decisionRegisteredQueue() {
        return QueueBuilder.durable(DECISION_REGISTERED_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DECISION_REGISTERED_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue decisionRegisteredDeadLetterQueue() {
        return QueueBuilder.durable(DECISION_REGISTERED_DLQ).build();
    }

    @Bean
    public Binding decisionRegisteredBinding() {
        return BindingBuilder.bind(decisionRegisteredQueue())
                .to(lexflowEventsExchange())
                .with(DECISION_REGISTERED_ROUTING_KEY);
    }

    @Bean
    public Binding decisionRegisteredDeadLetterBinding() {
        return BindingBuilder.bind(decisionRegisteredDeadLetterQueue())
                .to(lexflowDeadLetterExchange())
                .with(DECISION_REGISTERED_ROUTING_KEY);
    }

    /**
     * Converte os eventos para JSON.
     *
     * <p>JSON, e não serialização binária de Java: a mensagem na fila é um contrato entre serviços que
     * evoluem separadamente, e amarrá-la a classes Java impediria qualquer consumidor escrito em outra
     * linguagem — além de transformar uma renomeação de pacote em quebra de compatibilidade.
     *
     * <p>O {@link ObjectMapper} é montado aqui, e não herdado do contexto, para que o formato das
     * datas na fila não dependa de como a camada web foi configurada.
     *
     * <p>Declarar apenas o conversor, e não um {@code RabbitTemplate} próprio, é deliberado: o
     * template auto-configurado adota sozinho o único {@code MessageConverter} do contexto, e
     * substituí-lo por um construído à mão descartaria em silêncio as propriedades
     * {@code spring.rabbitmq.template.*} — entre elas o retry da publicação.
     */
    @Bean
    public MessageConverter lexflowMessageConverter() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                // ISO-8601 em vez de epoch numérico: legível em uma inspeção da fila.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
