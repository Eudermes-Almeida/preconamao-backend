package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;

// Um aparelho que usa a "Família" (scripts/020_familia.sql). Identificado só pelo hash da chave
// secreta que o próprio aparelho gerou; o nome é o primeiro nome que a pessoa digitou.
@Entity
@Table(name = "familia_membro")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FamiliaMembroEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chave_hash", nullable = false, length = 64)
    private String chaveHash;

    @Column(name = "nome", length = 30)
    private String nome;

    @Column(name = "criado_em", nullable = false)
    private OffsetDateTime criadoEm;

    // Quando o serviço de push confirmou que o app deste aparelho foi removido (scripts/022); null =
    // nada confirmado. Qualquer acesso do aparelho limpa.
    @Column(name = "app_removido_em")
    private OffsetDateTime appRemovidoEm;

}
