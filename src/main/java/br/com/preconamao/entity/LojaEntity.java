package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// Loja parceira (scripts/016, 023, 027). Cada loja é um caso à parte: tem a sua origem de preços
// (PRICETAB enviado pelo agente ou API consultada pelo servidor), o seu formato e o seu dicionário
// (catálogo reaproveitável, scripts/027). A rede é só agrupamento comercial. O id não é gerado
// aqui: as lojas são cadastradas por script.
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

    // Geolocalização (scripts/023): o slug é o fim do endereço do QR code afixado na loja; sem
    // latitude/longitude a loja não aparece para o cliente.
    @Column(name = "slug", length = 60)
    private String slug;

    @Column(name = "nome_curto", length = 30)
    private String nomeCurto;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    // Raio de entrada: até onde a loja aparece para ser escolhida pela localização.
    @Column(name = "raio_m")
    private Integer raioM;

    // Raio de saída (scripts/024): até onde a escolha continua valendo; maior que o de entrada.
    @Column(name = "raio_saida_m")
    private Integer raioSaidaM;

    @Column(name = "posicao_atualizada_em")
    private OffsetDateTime posicaoAtualizadaEm;


    // ---- Multi-loja (scripts/027_multiloja.sql) ----

    public static final String ORIGEM_PRICETAB = "PRICETAB";
    public static final String ORIGEM_API = "API";

    // Agrupamento comercial (ofertas da rede, relatório consolidado); null = loja independente.
    @Column(name = "rede_id")
    private Integer redeId;

    @Column(name = "tipo_origem", nullable = false, length = 10)
    private String tipoOrigem;

    @Column(name = "formato_id")
    private Integer formatoId;

    @Column(name = "dicionario_id")
    private Integer dicionarioId;

    @Column(name = "dicionario_loja_id")
    private Integer dicionarioLojaId;

    // Origem API: de quantos em quantos minutos o servidor consulta a loja.
    @Column(name = "intervalo_coleta_min")
    private Integer intervaloColetaMin;

    @Column(name = "ultima_coleta_em")
    private OffsetDateTime ultimaColetaEm;

    // false = parceria pausada: o app trata como "loja indisponível" e nada é coletado.
    @Column(name = "ativa", nullable = false)
    private boolean ativa;

    // Fração dos produtos ativos que, se sumir de uma carga, retém a carga (regra dos 20%).
    @Column(name = "limite_inativacao", nullable = false, precision = 4, scale = 3)
    private BigDecimal limiteInativacao;

    // Vale para UMA carga (troca de sistema da loja) e desliga sozinho depois de aplicada.
    @Column(name = "liberar_proxima_carga", nullable = false)
    private boolean liberarProximaCarga;

    @Column(name = "liberada_por", length = 60)
    private String liberadaPor;

    @Column(name = "liberada_em")
    private OffsetDateTime liberadaEm;

    // Desde quando o arquivo/dados da loja (hash_informado) diferem do aplicado; null = iguais.
    @Column(name = "hash_divergente_desde")
    private OffsetDateTime hashDivergenteDesde;

    // Dicionário da loja mudou: o agendador recalcula descrições, setor e pré-lista.
    @Column(name = "reprocessar_dicionario", nullable = false)
    private boolean reprocessarDicionario;

    // Etiqueta da balança: posições a partir de 0 dentro do EAN-13 (ver EtiquetaBalanca).
    @Column(name = "etiqueta_prefixo", nullable = false, length = 2)
    private String etiquetaPrefixo;

    @Column(name = "etiqueta_codigo_inicio", nullable = false)
    private Integer etiquetaCodigoInicio;

    @Column(name = "etiqueta_codigo_tamanho", nullable = false)
    private Integer etiquetaCodigoTamanho;

    @Column(name = "etiqueta_valor_inicio", nullable = false)
    private Integer etiquetaValorInicio;

    @Column(name = "etiqueta_valor_tamanho", nullable = false)
    private Integer etiquetaValorTamanho;

    // O código interno do PRICETAB termina com dígito verificador (0000000040556 = 4055 + 6)?
    @Column(name = "etiqueta_interno_dv", nullable = false)
    private boolean etiquetaInternoDv;

}
