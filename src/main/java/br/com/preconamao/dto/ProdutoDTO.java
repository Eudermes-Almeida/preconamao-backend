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

    // Produto de balança. Buscado pelo nome ou pelo código interno, precoCentavos é o preço do
    // quilo (não dá para pôr no carrinho sem pesar); lido da etiqueta, ver etiquetaBalanca.
    private boolean vendidoPorKg;

    // Preço do quilo; nulo em produto que não é de balança.
    private Integer precoKgCentavos;

    // Veio da etiqueta impressa pela balança: codigoBarras é o da etiqueta e precoCentavos é o
    // valor total dela (o que o caixa cobra).
    private boolean etiquetaBalanca;

}
