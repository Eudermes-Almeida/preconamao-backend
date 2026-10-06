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

    // ---- Multi-loja (regras 18, 19, 20, 4b, 22) ----

    // Por que o preço não é exibido (precoConfiavel = false): ZERO (preço 0,00 na origem),
    // CONFLITO (código repetido com preços diferentes) ou PROTECAO (loja sem sinal / dados não
    // aplicados). Null quando o preço é exibido.
    private String motivoSemPreco;

    // Produto interno de loja que não informa a unidade: o app mostra "preço de balança (por kg
    // ou unidade), confira na etiqueta" em vez de chutar "o quilo" ou "unidade".
    private boolean precoBalancaIndefinido;

    // Com promoção válida hoje: precoCentavos é o promocional e precoNormalCentavos o de venda
    // ("de R$ X por R$ Y, até dd/mm"). Sem promoção: null.
    private Integer precoNormalCentavos;
    private String promocaoAte;

    // Preço a partir de N unidades (informativo).
    private Integer atacadoCentavos;
    private Integer atacadoQuantidade;

    // "Leve 3, pague 2" — só texto, o app não faz conta.
    private String condicao;

    // Descrição cortada pela origem (PRICETAB de 16 posições): termina com "…" e o "ouvir preço"
    // não fala a última palavra.
    private boolean descricaoCortada;

}
