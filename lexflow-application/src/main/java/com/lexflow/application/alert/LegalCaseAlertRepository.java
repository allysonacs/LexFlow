package com.lexflow.application.alert;

import com.lexflow.domain.alert.LegalCaseAlert;
import java.util.List;
import java.util.UUID;

/** Porta de saída dos alertas de demanda. */
public interface LegalCaseAlertRepository {

    void save(LegalCaseAlert alert);

    /** Alertas de uma demanda, do mais antigo para o mais recente. */
    List<LegalCaseAlert> findByLegalCaseId(UUID legalCaseId);
}
