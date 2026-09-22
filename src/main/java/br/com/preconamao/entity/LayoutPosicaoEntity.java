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

    // Linha do layout (1 a 3, topo pra baixo).
    @Column(name = "quarteirao", nullable = false)
    private Integer quarteirao;

    // ESQUERDA/DIREITA (face da gôndola) ou CENTRO (balcão de atendimento: padaria,
    // açougue, farmácia/drogaria — sem gôndola de dois lados).
    @Column(name = "lado", nullable = false, length = 10)
    private String lado;

}
