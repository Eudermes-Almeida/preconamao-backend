package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.*;

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

    @Column(name = "codigo_barras", nullable = false, length = 13)
    private String codigoBarras;

    @Column(name = "descricao", nullable = false, length = 40)
    private String descricao;

    // Preço em centavos, no mesmo formato inteiro que já vem do PRICETAB.TXT da Gertec
    // (ex: "0000000389" = 389 centavos) — evita erro de arredondamento com decimal.
    @Column(name = "preco_centavos", nullable = false)
    private Integer precoCentavos;

}
