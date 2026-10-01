package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;

// Inscrição de um aparelho no serviço de push do navegador, para o aviso da "Família" chegar com
// o app fechado (scripts/021_familia_avisos.sql).
@Entity
@Table(name = "familia_aviso_inscricao")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FamiliaAvisoInscricaoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "membro_id", nullable = false)
    private Long membroId;

    @Column(name = "endpoint", nullable = false, unique = true, length = 1000)
    private String endpoint;

    @Column(name = "p256dh", nullable = false, length = 120)
    private String p256dh;

    @Column(name = "auth", nullable = false, length = 40)
    private String auth;

    @Column(name = "criado_em", nullable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private OffsetDateTime atualizadoEm;

}
