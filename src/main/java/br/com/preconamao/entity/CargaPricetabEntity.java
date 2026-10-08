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
    public static final String COMPLETA = "COMPLETA";
    public static final String PARCIAL = "PARCIAL";

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

    // ---- Multi-loja (scripts/027) ----

    // PRICETAB (agente da loja) ou API (coleta do servidor).
    @Column(name = "origem", nullable = false, length = 10)
    private String origem;

    // COMPLETA (o que não veio é inativado) ou PARCIAL (coleta incremental: só os produtos do ERP
    // informados podem perder códigos) — scripts/033.
    @Builder.Default
    @Column(name = "tipo", nullable = false, length = 10)
    private String tipo = COMPLETA;

    // Nome da cópia enviada pelo agente (PRICETAB_<loja>_<data-hora>.TXT): confere com a chave.
    @Column(name = "nome_arquivo", length = 120)
    private String nomeArquivo;

    // Onde o arquivo ficou no armazenamento (o conteúdo não fica mais no banco).
    @Column(name = "caminho_arquivo", length = 200)
    private String caminhoArquivo;

    @Column(name = "linhas_sem_preco")
    private Integer linhasSemPreco;

    @Column(name = "codigos_repetidos")
    private Integer codigosRepetidos;

    @Column(name = "conflitos_preco")
    private Integer conflitosPreco;

}
