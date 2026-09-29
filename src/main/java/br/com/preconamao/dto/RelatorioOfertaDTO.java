package br.com.preconamao.dto;

import lombok.*;

// Uma linha do relatório de mídias: os números de uma oferta no período. Exibições e "localizar"
// contam toques/aparições; favoritos e pré-lista contam APARELHOS (marcar e desmarcar várias vezes
// não infla o número); alcance = aparelhos distintos que viram a oferta.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RelatorioOfertaDTO {

    private String codigoBarras;

    private String descricao;

    private long exibicoes;

    private long exibicoesAnuncio;

    private long exibicoesTela;

    private long alcance;

    private long favoritos;

    private long desfavoritos;

    private long localizar;

    private long localizarAnuncio;

    private long localizarTela;

    private long preLista;

    private long preListaAnuncio;

    private long preListaTela;

}
