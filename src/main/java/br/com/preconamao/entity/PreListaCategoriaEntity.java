package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

// Categoria (accordion) da pré-lista de compras. Só leitura pela aplicação: as linhas vêm do
// seed de modelagem_dados_postgres/scripts/013_pre_lista.sql (ids fixos, sem @GeneratedValue).
@Entity
@Table(name = "pre_lista_categoria")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PreListaCategoriaEntity {

    @Id
    private Long id;

    @Column(name = "nome", nullable = false, length = 60)
    private String nome;

    @Column(name = "ordem", nullable = false)
    private Integer ordem;

}
