package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

// Itens da pré-lista enviados de um membro da "Família" para outro (scripts/020_familia.sql).
@Entity
@Table(name = "familia_lista")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FamiliaListaEntity {

    public static final String PENDENTE = "PENDENTE";
    public static final String ACEITA = "ACEITA";
    public static final String RECUSADA = "RECUSADA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loja_id", nullable = false)
    private Integer lojaId;

    @Column(name = "de_membro_id", nullable = false)
    private Long deMembroId;

    @Column(name = "para_membro_id", nullable = false)
    private Long paraMembroId;

    // JSON de ConteudoListaDTO: {"itens": {...}, "produtos": {...}}.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conteudo", nullable = false)
    private String conteudo;

    // Linhas da lista (itens + produtos), para o relatório.
    @Column(name = "quantidade_itens", nullable = false)
    private Integer quantidadeItens;

    @Column(name = "situacao", nullable = false, length = 10)
    private String situacao;

    @Column(name = "enviada_em", nullable = false)
    private OffsetDateTime enviadaEm;

    @Column(name = "resolvida_em")
    private OffsetDateTime resolvidaEm;

}
