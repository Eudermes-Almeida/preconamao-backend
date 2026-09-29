package br.com.preconamao.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;

// Um PRICETAB recebido do agente de uma loja e o resumo do processamento
// (scripts/016_carga_automatica.sql). Situações: RECEBIDA (aguardando), CONCLUIDA, RETIDA (não
// aplicada por segurança), ERRO e IGNORADA (substituída por um arquivo mais novo antes de processar).
@Entity
@Table(name = "carga_pricetab")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CargaPricetabEntity {

    public static final String RECEBIDA = "RECEBIDA";
    public static final String CONCLUIDA = "CONCLUIDA";
    public static final String RETIDA = "RETIDA";
    public static final String ERRO = "ERRO";
    public static final String IGNORADA = "IGNORADA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loja_id", nullable = false)
    private Integer lojaId;

    @Column(name = "recebida_em", nullable = false)
    private OffsetDateTime recebidaEm;

    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    @Column(name = "tamanho_bytes", nullable = false)
    private Integer tamanhoBytes;

    // Arquivo original, em Latin-1 como veio; LAZY para as consultas de situação não trazerem
    // 1 MB à toa. Fica null nas cargas antigas (ver CargaPricetabService.limparArquivosAntigos).
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "arquivo")
    private byte[] arquivo;

    @Column(name = "situacao", nullable = false, length = 10)
    private String situacao;

    @Column(name = "processada_em")
    private OffsetDateTime processadaEm;

    @Column(name = "linhas_total")
    private Integer linhasTotal;

    @Column(name = "linhas_invalidas")
    private Integer linhasInvalidas;

    @Column(name = "novos")
    private Integer novos;

    @Column(name = "precos_alterados")
    private Integer precosAlterados;

    @Column(name = "descricoes_alteradas")
    private Integer descricoesAlteradas;

    @Column(name = "inativados")
    private Integer inativados;

    @Column(name = "reativados")
    private Integer reativados;

    @Column(name = "mensagem", columnDefinition = "TEXT")
    private String mensagem;

}
