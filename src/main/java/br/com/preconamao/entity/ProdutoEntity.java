package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "produtos", uniqueConstraints = @UniqueConstraint(columnNames = "codigo_barras"))
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProdutoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo_barras", nullable = false, length = 14)
    private String codigoBarras;

    // Como veio do PRICETAB (abreviada: "IOG BATAVO 170G MOR").
    @Column(name = "descricao", nullable = false, length = 40)
    private String descricao;

    // Com as abreviações por extenso ("IOGURTE BATAVO 170G MORANGO"), calculada pelo banco em toda
    // carga (expandir_descricao(), scripts/025_pricetab_real.sql). É a que o app mostra.
    @Column(name = "descricao_expandida", length = 120)
    private String descricaoExpandida;

    // Código interno que a etiqueta da balança traz (ver codigo_balanca_de() no script 025).
    @Column(name = "codigo_balanca", length = 8)
    private String codigoBalanca;

    // Menor código entre os de mesma descrição e mesmo preço (códigos auxiliares do mesmo
    // produto): a busca por descrição mostra cada grupo uma vez só.
    @Column(name = "grupo_codigo", length = 14)
    private String grupoCodigo;

    // Preço em centavos, no mesmo formato inteiro que já vem do PRICETAB.TXT da Gertec
    // (ex: "0000000389" = 389 centavos) — evita erro de arredondamento com decimal.
    @Column(name = "preco_centavos", nullable = false)
    private Integer precoCentavos;

    // Onde o produto fica na loja piloto; NULL quando o categorizador (ver carga_pricetab.py,
    // no repo de modelagem) não achou palavra-chave batendo com a descrição.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "layout_id")
    private LayoutPosicaoEntity layoutPosicao;

    // Item da pré-lista de compras que este produto atende (ex.: "DETERGENTE YPE NEUTRO" ->
    // Detergente); NULL quando nenhum termo casou. Resolvido no banco pela função
    // vincular_produtos_pre_lista() (scripts/013_pre_lista.sql), nunca gravado pela aplicação.
    @Column(name = "pre_lista_item_id")
    private Long preListaItemId;

    // Produto de balança (scripts/015_produtos_pesaveis.sql): codigoBarras é o código interno
    // que vem na etiqueta (ex.: "2984") e precoCentavos é o preço do quilo.
    @Column(name = "vendido_por_kg", nullable = false)
    private boolean vendidoPorKg;

    // Sumiu do último PRICETAB: some das buscas, mas não é apagado (volta se reaparecer).
    // Ver aplicar_carga_pricetab() em scripts/016_carga_automatica.sql.
    @Column(name = "ativo", nullable = false)
    private boolean ativo;

    // Localização corrigida à mão: a carga não recalcula layout_id.
    @Column(name = "layout_manual", nullable = false)
    private boolean layoutManual;

    // Última mudança de preço, descrição ou situação feita por uma carga.
    @Column(name = "atualizado_em", nullable = false)
    private OffsetDateTime atualizadoEm;

}
