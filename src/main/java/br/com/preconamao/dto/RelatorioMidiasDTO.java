package br.com.preconamao.dto;

import lombok.*;

import java.util.List;

// GET /relatorios/midias: totais do período (soma das ofertas, exceto "aparelhos", que é o total de
// aparelhos distintos com algum evento), uma linha por oferta com evento e os últimos eventos.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RelatorioMidiasDTO {

    private String periodo;

    // Início do período (meia-noite de Brasília) e momento da consulta.
    private String de;

    private String ate;

    private long aparelhos;

    private RelatorioOfertaDTO totais;

    private List<RelatorioOfertaDTO> ofertas;

    private List<EventoRecenteDTO> recentes;

}
