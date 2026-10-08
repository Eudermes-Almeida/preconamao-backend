package br.com.preconamao.service;

import br.com.preconamao.dto.LojaPublicaDTO;
import br.com.preconamao.dto.PosicaoLojaDTO;
import br.com.preconamao.entity.FormatoOrigemEntity;
import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.entity.RedeEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class LojaService {

    // Consulta do app sem ?loja= (regra 23b): em produção, a loja padrão (app antigo guardado no
    // celular de algum testador); no DES (loja.exigir-na-consulta=true), erro — um esquecimento no
    // código aparece no teste em vez de cair calado na loja 1.
    @ConfigProperty(name = "loja.id-padrao", defaultValue = "1")
    Integer lojaPadraoId;

    @ConfigProperty(name = "loja.exigir-na-consulta", defaultValue = "false")
    boolean exigirLojaNaConsulta;

    private static final int RAIO_SAIDA_PADRAO_M = 300;

    @Inject
    EntityManager entityManager;

    // precoConferidoEm: último sinal de vida com o arquivo da loja igual ao aplicado (null quando a
    // proteção está desligada ou o preço não é confiável).
    public record SituacaoPreco(boolean confiavel, OffsetDateTime conferidoEm) {
    }

    // Loja pedida pelo app que não pode ser atendida: status HTTP + mensagem para o cliente.
    public static class LojaIndisponivel extends RuntimeException {
        public final int status;

        LojaIndisponivel(int status, String mensagem) {
            super(mensagem);
            this.status = status;
        }
    }

    // Loja da consulta do app (?loja=<id>, regra 23a).
    @Transactional
    public LojaEntity lojaDaConsulta(Integer lojaId) {
        if (lojaId == null) {
            if (exigirLojaNaConsulta) {
                throw new LojaIndisponivel(400, "Loja não informada.");
            }
            lojaId = lojaPadraoId;
        }
        LojaEntity loja = entityManager.find(LojaEntity.class, lojaId);
        if (loja == null || !loja.isAtiva()) {
            throw new LojaIndisponivel(404, "Esta loja não está mais disponível no Simplifica Compras.");
        }
        return loja;
    }

    @Transactional
    public FormatoOrigemEntity formato(LojaEntity loja) {
        return loja.getFormatoId() == null ? null : entityManager.find(FormatoOrigemEntity.class, loja.getFormatoId());
    }

    // Chave do cabeçalho X-Chave-Loja -> loja. A comparação é pelo hash: a chave nunca é gravada.
    @Transactional
    public Optional<LojaEntity> autenticar(String chave) {
        if (chave == null || chave.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(entityManager.createQuery(
                            "SELECT l FROM LojaEntity l WHERE l.chaveHash = :hash", LojaEntity.class)
                    .setParameter("hash", sha256(chave.trim().getBytes(StandardCharsets.UTF_8)))
                    .getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    // Só 1 agente por loja (scripts/034, segurança R10): o 1º agente que se identifica fica
    // registrado; outro (a mesma chave copiada em outro computador) é recusado. Sem identificação
    // (agente PRICETAB antigo) continua aceito enquanto nenhum agente se registrou. Trocar de
    // computador = limpar loja.agente_id. Devolve a mensagem de recusa ou null.
    @Transactional
    public String conferirAgente(LojaEntity loja, String agenteId) {
        LojaEntity atual = entityManager.find(LojaEntity.class, loja.getId());
        if (agenteId == null || agenteId.isBlank()) {
            return atual.getAgenteId() == null ? null
                    : "Esta loja já tem um agente registrado; este agente não se identificou (versão antiga?).";
        }
        String id = agenteId.trim();
        if (id.length() > 80 || !id.matches("[A-Za-z0-9._:-]+")) {
            return "Identificação do agente inválida.";
        }
        if (atual.getAgenteId() == null) {
            atual.setAgenteId(id);
            return null;
        }
        return atual.getAgenteId().equals(id) ? null
                : "Outro agente já está registrado para esta loja (só 1 agente por loja). Para trocar de computador, peça a liberação.";
    }

    // Chave do cabeçalho X-Chave-Relatorio (aba administrativa) -> loja. Separada da chave do agente.
    @Transactional
    public Optional<LojaEntity> autenticarRelatorio(String chave) {
        if (chave == null || chave.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(entityManager.createQuery(
                            "SELECT l FROM LojaEntity l WHERE l.chaveRelatorioHash = :hash", LojaEntity.class)
                    .setParameter("hash", sha256(chave.trim().getBytes(StandardCharsets.UTF_8)))
                    .getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    // Mesma ideia, para o relatório consolidado do dono da rede (regra 6b).
    @Transactional
    public Optional<RedeEntity> autenticarRelatorioRede(String chave) {
        if (chave == null || chave.isBlank()) {
            return Optional.empty();
        }
        return entityManager.createQuery("SELECT r FROM RedeEntity r WHERE r.chaveRelatorioHash = :hash", RedeEntity.class)
                .setParameter("hash", sha256(chave.trim().getBytes(StandardCharsets.UTF_8)))
                .getResultStream().findFirst();
    }

    // Proteção de preço (regra 11g), por loja. Sem limite = desligada (preço sempre exibido).
    // Confiável = sinal recente (agente vivo, ou a última coleta da API deu certo) E os dados da
    // loja iguais aos aplicados — ou diferentes há menos que o limite (tempo da fila e da carga).
    @Transactional
    public SituacaoPreco situacaoPreco(LojaEntity loja) {
        LojaEntity atual = entityManager.find(LojaEntity.class, loja.getId());
        if (atual == null || atual.getLimiteSemSinalMin() == null) {
            return new SituacaoPreco(true, null);
        }
        boolean arquivoEmDia = atual.getHashAplicado() != null
                && (Objects.equals(atual.getHashInformado(), atual.getHashAplicado())
                || (atual.getHashDivergenteDesde() != null && atual.getHashDivergenteDesde()
                .isAfter(OffsetDateTime.now().minusMinutes(atual.getLimiteSemSinalMin()))));
        boolean confiavel = sinalRecente(atual) && arquivoEmDia;
        return new SituacaoPreco(confiavel, confiavel ? atual.getUltimoSinalEm() : null);
    }

    // A loja informou o hash dos dados que tem (sinal do agente, arquivo recebido, coleta da API):
    // marca desde quando ele difere do aplicado (regra 11g).
    public void registrarHashInformado(LojaEntity loja, String hash) {
        loja.setHashInformado(hash);
        atualizarDivergencia(loja);
    }

    public void atualizarDivergencia(LojaEntity loja) {
        if (loja.getHashInformado() == null || Objects.equals(loja.getHashInformado(), loja.getHashAplicado())) {
            loja.setHashDivergenteDesde(null);
        } else if (loja.getHashDivergenteDesde() == null) {
            loja.setHashDivergenteDesde(OffsetDateTime.now());
        }
    }

    // Lojas que o cliente pode escolher: ativas e com posição cadastrada.
    @Transactional
    public List<LojaPublicaDTO> lojasComPosicao() {
        return entityManager.createQuery(
                        "SELECT l FROM LojaEntity l WHERE l.ativa = true AND l.slug IS NOT NULL AND l.latitude IS NOT NULL"
                                + " AND l.longitude IS NOT NULL AND l.raioM IS NOT NULL ORDER BY l.id", LojaEntity.class)
                .getResultList().stream()
                .map(l -> LojaPublicaDTO.builder()
                        .id(l.getId())
                        .slug(l.getSlug())
                        .nome(l.getNomeCurto() != null ? l.getNomeCurto() : l.getNome())
                        .latitude(l.getLatitude().doubleValue())
                        .longitude(l.getLongitude().doubleValue())
                        .raioM(l.getRaioM())
                        // Sem raio de saída cadastrado: 300 m, e nunca menor que o de entrada.
                        .raioSaidaM(Math.max(l.getRaioM(), l.getRaioSaidaM() != null ? l.getRaioSaidaM() : RAIO_SAIDA_PADRAO_M))
                        .origem(l.getTipoOrigem())
                        .formato(Optional.ofNullable(formato(l)).map(FormatoOrigemEntity::getNome).orElse(null))
                        .build())
                .toList();
    }

    // Grava a posição medida de dentro da loja. Devolve a mensagem de erro, ou null se gravou.
    @Transactional
    public String registrarPosicao(Integer lojaId, PosicaoLojaDTO posicao) {
        if (posicao == null || posicao.getLatitude() == null || posicao.getLongitude() == null
                || Math.abs(posicao.getLatitude()) > 90 || Math.abs(posicao.getLongitude()) > 180) {
            return "Latitude e longitude são obrigatórias e precisam ser válidas.";
        }
        if (posicao.getRaioM() != null && (posicao.getRaioM() < 10 || posicao.getRaioM() > 2000)) {
            return "O raio deve ficar entre 10 e 2000 metros.";
        }
        LojaEntity loja = entityManager.find(LojaEntity.class, lojaId);
        loja.setLatitude(BigDecimal.valueOf(posicao.getLatitude()).setScale(6, RoundingMode.HALF_UP));
        loja.setLongitude(BigDecimal.valueOf(posicao.getLongitude()).setScale(6, RoundingMode.HALF_UP));
        if (posicao.getRaioM() != null) {
            loja.setRaioM(posicao.getRaioM());
        }
        loja.setPosicaoAtualizadaEm(OffsetDateTime.now());
        return null;
    }

    public boolean sinalRecente(LojaEntity loja) {
        return loja.getUltimoSinalEm() != null && loja.getLimiteSemSinalMin() != null
                && loja.getUltimoSinalEm().isAfter(OffsetDateTime.now().minusMinutes(loja.getLimiteSemSinalMin()));
    }

    public static String sha256(byte[] dados) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
