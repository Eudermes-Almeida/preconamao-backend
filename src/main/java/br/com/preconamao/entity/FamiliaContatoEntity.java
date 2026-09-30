package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;

// Um lado da ligação da "Família": o membro enxerga o contato com o apelido que deu a ele. Cada
// ligação tem duas linhas, uma para cada sentido (scripts/020_familia.sql).
@Entity
@Table(name = "familia_contato")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FamiliaContatoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "membro_id", nullable = false)
    private Long membroId;

    @Column(name = "contato_id", nullable = false)
    private Long contatoId;

    @Column(name = "apelido", nullable = false, length = 30)
    private String apelido;

    @Column(name = "criado_em", nullable = false)
    private OffsetDateTime criadoEm;

}
