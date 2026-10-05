package br.com.preconamao.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProdutoDTO {

    private String codigoBarras;

    // Descrição com as abreviações por extenso (a que o app mostra e fala).
    private String descricao;

    // Como veio do PRICETAB, quando é diferente da descrição acima (o app mostra pequena, embaixo,
    // para o cliente conferir com a etiqueta da gôndola); nula quando não há o que expandir.
    private String descricaoOriginal;

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

    // false = o app não pode garantir que o preço está atualizado (agente da loja sem sinal ou
    // arquivo da loja diferente do aplicado): o app esconde o preço. Etiqueta de balança é sempre
    // true (o valor vem impresso).
    private boolean precoConfiavel;

    // Quando o preço foi conferido pela última vez (sinal de vida do agente, ISO-8601); null se a
    // proteção estiver desligada ou o preço não for confiável.
    private String precoConferidoEm;

}
