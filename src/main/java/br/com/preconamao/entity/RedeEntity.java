package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

// Rede de lojas (scripts/027): só agrupamento comercial — ofertas negociadas com a rede inteira,
// relatório consolidado do dono e contrato. Não decide nada técnico (cada loja tem o seu formato e
// o seu dicionário).
@Entity
@Table(name = "rede")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RedeEntity {

    @Id
    private Integer id;

    @Column(name = "nome", nullable = false, length = 80)
    private String nome;

    @Column(name = "slug", length = 60)
    private String slug;

    // SHA-256 da chave do relatório consolidado da rede; null = sem acesso de rede.
    @Column(name = "chave_relatorio_hash", length = 64)
    private String chaveRelatorioHash;
}
