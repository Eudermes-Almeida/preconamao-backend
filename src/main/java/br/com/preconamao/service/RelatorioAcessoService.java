package br.com.preconamao.service;

import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.entity.RedeEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

// Quem pode ver qual relatório (regra 6 do multi-loja): a chave da LOJA vê só ela; a da REDE, as
// lojas da rede; a GERAL (de quem opera o sistema, SHA-256 em relatorio.chave-geral-hash), todas.
@ApplicationScoped
public class RelatorioAcessoService {

    public static final String LOJA = "LOJA";
    public static final String REDE = "REDE";
    public static final String GERAL = "GERAL";

    // Vazio = sem chave geral neste ambiente.
    @ConfigProperty(name = "relatorio.chave-geral-hash")
    Optional<String> chaveGeralHash;

    @Inject
    EntityManager entityManager;

    @Inject
    LojaService lojaService;

    public record Acesso(String tipo, List<LojaEntity> lojas) {
    }

    @Transactional
    public Optional<Acesso> autenticar(String chave) {
        if (chave == null || chave.isBlank()) {
            return Optional.empty();
        }
        String hash = LojaService.sha256(chave.trim().getBytes(StandardCharsets.UTF_8));
        if (chaveGeralHash.isPresent() && !chaveGeralHash.get().isBlank() && chaveGeralHash.get().equalsIgnoreCase(hash)) {
            return Optional.of(new Acesso(GERAL, entityManager.createQuery(
                    "SELECT l FROM LojaEntity l ORDER BY l.id", LojaEntity.class).getResultList()));
        }
        Optional<LojaEntity> loja = lojaService.autenticarRelatorio(chave);
        if (loja.isPresent()) {
            return Optional.of(new Acesso(LOJA, List.of(loja.get())));
        }
        Optional<RedeEntity> rede = lojaService.autenticarRelatorioRede(chave);
        return rede.map(r -> new Acesso(REDE, entityManager.createQuery(
                        "SELECT l FROM LojaEntity l WHERE l.redeId = :rede ORDER BY l.id", LojaEntity.class)
                .setParameter("rede", r.getId()).getResultList()));
    }
}
