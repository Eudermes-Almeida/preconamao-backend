package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;

// Loja que envia o PRICETAB pelo agente (scripts/016_carga_automatica.sql). Por enquanto só a loja
// piloto (id 1); o id não é gerado aqui porque as lojas são cadastradas por script.
@Entity
@Table(name = "loja")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LojaEntity {

    @Id
    private Integer id;

    @Column(name = "nome", nullable = false, length = 80)
    private String nome;

    // SHA-256 (hex) da chave do agente; a chave em si nunca é gravada.
    @Column(name = "chave_hash", nullable = false, length = 64)
    private String chaveHash;

    // Minutos sem sinal de vida até o app esconder os preços; null = proteção desligada.
    @Column(name = "limite_sem_sinal_min")
    private Integer limiteSemSinalMin;

    @Column(name = "ultimo_sinal_em")
    private OffsetDateTime ultimoSinalEm;

    // Hash do PRICETAB que o agente diz ter e do último aplicado no banco: diferentes = o app
    // não sabe se o preço exibido está certo.
    @Column(name = "hash_informado", length = 64)
    private String hashInformado;

    @Column(name = "hash_aplicado", length = 64)
    private String hashAplicado;

    // Problema em andamento já avisado por e-mail (null = tudo certo) e quando foi o último aviso.
    @Column(name = "alerta_ativo", length = 40)
    private String alertaAtivo;

    @Column(name = "alerta_enviado_em")
    private OffsetDateTime alertaEnviadoEm;

    // SHA-256 da chave de leitura do relatório de mídias (scripts/018); null = relatório fechado.
    @Column(name = "chave_relatorio_hash", length = 64)
    private String chaveRelatorioHash;

}
