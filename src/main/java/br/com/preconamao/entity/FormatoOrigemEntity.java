package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Ficha de parâmetros da origem de preços (scripts/027): o leitor de PRICETAB é um só e obedece à
// ficha (separador, tamanho da descrição, descrição cortada...). Loja nova com um PRICETAB
// diferente = ficha nova, sem código novo. Cadastrada por script.
@Entity
@Table(name = "formato_origem")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FormatoOrigemEntity {

    @Id
    private Integer id;

    @Column(name = "nome", nullable = false, length = 60)
    private String nome;

    // PRICETAB ou API.
    @Column(name = "tipo", nullable = false, length = 10)
    private String tipo;

    // JSON com os parâmetros (ver FormatoPricetab).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parametros", nullable = false, columnDefinition = "jsonb")
    private String parametros;

    @Column(name = "observacao", columnDefinition = "TEXT")
    private String observacao;
}
