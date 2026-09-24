package br.com.preconamao.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProdutoDTO {

    private String codigoBarras;

    private String descricao;

    private Integer precoCentavos;

    // Nulo quando o produto ainda não tem posição mapeada no layout da loja.
    private LocalizacaoDTO localizacao;

    // Item da pré-lista que o produto risca ao entrar no carrinho; nulo se não atende nenhum.
    private Long preListaItemId;

}
