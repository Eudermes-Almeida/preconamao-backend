package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.*;

// Intenção genérica de compra ("Detergente", "Arroz") dentro de uma categoria da pré-lista.
// Os produtos apontam para o item em produtos.pre_lista_item_id, resolvido no banco pelos
// termos de pre_lista_termo (ver scripts/013_pre_lista.sql) — a aplicação só lê.
@Entity
@Table(name = "pre_lista_item")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PreListaItemEntity {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id", nullable = false)
    private PreListaCategoriaEntity categoria;

    @Column(name = "nome", nullable = false, length = 60)
    private String nome;

    @Column(name = "ordem", nullable = false)
    private Integer ordem;

}
