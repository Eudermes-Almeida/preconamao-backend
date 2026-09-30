package br.com.preconamao.dto;

import lombok.*;

// "Família" no período do relatório de mídias: ligações feitas (convites aceitos) e listas trocadas.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RelatorioFamiliaDTO {

    private long ligacoes;

    private long listasEnviadas;

    private long listasAceitas;

    private long listasRecusadas;

    // Linhas (itens + produtos) de todas as listas enviadas.
    private long itensEnviados;

}
