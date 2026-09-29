package br.com.preconamao.dto;

import lombok.*;

import java.util.List;

// Painel da loja para o agente/instalador e para conferência: preço confiável, último sinal de
// vida, alerta em andamento e as últimas cargas.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SituacaoLojaDTO {

    private String loja;

    private boolean precoConfiavel;

    private Integer limiteSemSinalMin;

    private String ultimoSinalEm;

    private boolean arquivoAplicado;

    private String alertaAtivo;

    private List<CargaRecebidaDTO> ultimasCargas;

}
