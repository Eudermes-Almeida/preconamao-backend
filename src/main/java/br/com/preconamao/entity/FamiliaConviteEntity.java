package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;

// Convite para ligar dois aparelhos da "Família": link do WhatsApp ou código de 8 caracteres,
// de uso único e com validade (scripts/020_familia.sql).
@Entity
@Table(name = "familia_convite")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FamiliaConviteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo", nullable = false, length = 8)
    private String codigo;

    @Column(name = "loja_id", nullable = false)
    private Integer lojaId;

    @Column(name = "de_membro_id", nullable = false)
    private Long deMembroId;

    // Como quem convidou vai chamar quem aceitar.
    @Column(name = "apelido_convidado", nullable = false, length = 30)
    private String apelidoConvidado;

    @Column(name = "criado_em", nullable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "expira_em", nullable = false)
    private OffsetDateTime expiraEm;

    @Column(name = "aceito_em")
    private OffsetDateTime aceitoEm;

    @Column(name = "aceito_por_id")
    private Long aceitoPorId;

}
