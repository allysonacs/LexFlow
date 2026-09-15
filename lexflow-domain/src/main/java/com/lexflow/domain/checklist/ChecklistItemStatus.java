package com.lexflow.domain.checklist;

/** Situação de um item de checklist documental (seção 6 da base de conhecimento). */
public enum ChecklistItemStatus {

    /** Item criado, ainda não avaliado. */
    PENDING,

    /** Documento exigido foi vinculado ao item. */
    SATISFIED,

    /** Avaliado e sem o documento exigido. */
    MISSING
}
