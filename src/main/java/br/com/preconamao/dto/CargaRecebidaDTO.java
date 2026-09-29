package br.com.preconamao.dto;

import lombok.*;

// Situação de uma carga do PRICETAB (resposta ao agente e à consulta de situação).
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CargaRecebidaDTO {

    private Long id;

    // RECEBIDA, CONCLUIDA, RETIDA, ERRO, IGNORADA; ou JA_APLICADO quando o arquivo enviado é o
    // mesmo que já está no banco (nenhuma carga nova é criada).
    private String situacao;

    private String recebidaEm;

    private String processadaEm;

    private Integer linhasTotal;

    private Integer linhasInvalidas;

    private Integer novos;

    private Integer precosAlterados;

    private Integer descricoesAlteradas;

    private Integer inativados;

    private Integer reativados;

    private String mensagem;

    // true só na resposta que criou esta carga (a API responde 202).
    private boolean novaCarga;

}
