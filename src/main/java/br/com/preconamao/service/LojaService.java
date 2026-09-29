package br.com.preconamao.service;

import br.com.preconamao.entity.LojaEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class LojaService {

    // O catálogo ainda é um só (produtos não tem loja_id): a "confiabilidade" do preço exibido é a
    // da loja piloto. Com várias lojas, vira parâmetro da consulta.
    @ConfigProperty(name = "loja.id-padrao", defaultValue = "1")
    Integer lojaPadraoId;

    @Inject
    EntityManager entityManager;

    // precoConferidoEm: último sinal de vida com o arquivo da loja igual ao aplicado (null quando a
    // proteção está desligada ou o preço não é confiável).
    public record SituacaoPreco(boolean confiavel, OffsetDateTime conferidoEm) {
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

    @Transactional
    public SituacaoPreco situacaoPreco() {
        LojaEntity loja = entityManager.find(LojaEntity.class, lojaPadraoId);
        if (loja == null || loja.getLimiteSemSinalMin() == null) {
            return new SituacaoPreco(true, null);
        }
        boolean confiavel = sinalRecente(loja) && loja.getHashAplicado() != null
                && Objects.equals(loja.getHashInformado(), loja.getHashAplicado());
        return new SituacaoPreco(confiavel, confiavel ? loja.getUltimoSinalEm() : null);
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
