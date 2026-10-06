package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;

// Credencial da API do sistema da loja (scripts/027, regra 12b do multi-loja). O segredo fica
// cifrado (CifraCredenciais); a chave da cifra só existe na variável de ambiente do servidor.
@Entity
@Table(name = "credencial_api")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CredencialApiEntity {

    @Id
    @Column(name = "loja_id")
    private Integer lojaId;

    // Endereço base da API (ex.: https://erp.loja.com.br/api).
    @Column(name = "url", nullable = false, length = 200)
    private String url;

    @Column(name = "usuario", nullable = false, length = 120)
    private String usuario;

    // Nunca sai do servidor: nem em log, nem em relatório, nem em resposta.
    @ToString.Exclude
    @Column(name = "segredo_cifrado", nullable = false, columnDefinition = "TEXT")
    private String segredoCifrado;

    @Column(name = "atualizada_em", nullable = false)
    private OffsetDateTime atualizadaEm;
}
