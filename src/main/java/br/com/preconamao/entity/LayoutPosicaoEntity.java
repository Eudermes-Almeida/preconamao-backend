package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

// Posição fixa de uma categoria no layout físico da loja piloto (ver
// modelagem_dados_postgres/scripts/010_layout_loja.sql). Tabela só de leitura pela
// aplicação: as 33 linhas vêm do seed, nunca são inseridas/alteradas pelo Quarkus —
// por isso o id não usa @GeneratedValue.
@Entity
@Table(name = "layout_posicao")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LayoutPosicaoEntity {

    @Id
    private Long id;

    @Column(name = "nome_setor", nullable = false, length = 50)
    private String nomeSetor;

    // Coluna do layout (1 a 5, esquerda pra direita).
    @Column(name = "rua", nullable = false)
    private Integer rua;

    // Linha do layout, identificada por letra (A a C, topo pra baixo) — não número, pra não
    // confundir com o número da rua ao ler o mapa.
    @Column(name = "quarteirao", nullable = false, length = 1)
    private String quarteirao;

    // ESQUERDA/DIREITA (face da gôndola) ou CENTRO (balcão de atendimento: padaria,
    // açougue, farmácia/drogaria — sem gôndola de dois lados).
    @Column(name = "lado", nullable = false, length = 10)
    private String lado;

}
