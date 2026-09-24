package br.com.preconamao.service;

import br.com.preconamao.dto.LocalizacaoDTO;
import br.com.preconamao.dto.ProdutoDTO;
import br.com.preconamao.entity.LayoutPosicaoEntity;
import br.com.preconamao.entity.ProdutoEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProdutoService {

    private static final int MAX_CANDIDATOS = 5;

    // Abaixo disso a similaridade é ruído (ex.: "creme dental" x "cremoso" dá ~0,31).
    private static final float LIMIAR_SIMILARIDADE = 0.4f;

    // Palavras de enchimento típicas de quem fala ("quero o veja", "qual o preço do ..."):
    // sem removê-las, cada uma dilui a similaridade com a descrição do produto.
    private static final Set<String> PALAVRAS_DE_ENCHIMENTO = Set.of(
            "quero", "queria", "qual", "quanto", "custa", "preco", "produto", "por", "favor",
            "me", "mostra", "mostre", "ver", "consultar",
            "o", "a", "os", "as", "um", "uma", "de", "do", "da", "dos", "das", "e");

    @Inject
    EntityManager entityManager;

    @Transactional
    public Optional<ProdutoDTO> buscaPorCodigoBarras(String codigoBarras) {
        String codigoTratado = codigoBarras.trim();

        try {
            // LEFT JOIN FETCH: traz a localização (se houver) na mesma consulta, em vez de uma
            // segunda ida ao banco só para o lazy load de layoutPosicao.
            ProdutoEntity produtoEntity = entityManager.createQuery(
                            "SELECT p FROM ProdutoEntity p LEFT JOIN FETCH p.layoutPosicao "
                                    + "WHERE p.codigoBarras = :codigoBarras",
                            ProdutoEntity.class)
                    .setParameter("codigoBarras", codigoTratado)
                    .getSingleResult();

            return Optional.of(mapToDTO(produtoEntity));
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    // Busca por descrição falada. JPQL não conhece pg_trgm, então esta é a única consulta nativa:
    // word_similarity compara o texto falado com o trecho mais parecido da descrição, sem depender
    // da ordem das palavras ("veja multiuso" acha "MULTIUSO VEJA 500ML").
    @Transactional
    @SuppressWarnings("unchecked")
    public List<ProdutoDTO> buscaPorDescricao(String descricao) {
        String textoTratado = removePalavrasDeEnchimento(descricao);

        if (textoTratado.isEmpty()) {
            return List.of();
        }

        List<ProdutoEntity> produtos = entityManager.createNativeQuery(
                        "SELECT p.* FROM produtos p "
                                + "WHERE word_similarity(unaccent(:texto), unaccent(lower(p.descricao))) >= :limiar "
                                + "ORDER BY word_similarity(unaccent(:texto), unaccent(lower(p.descricao))) DESC, p.descricao "
                                + "LIMIT :limite",
                        ProdutoEntity.class)
                .setParameter("texto", textoTratado)
                .setParameter("limiar", LIMIAR_SIMILARIDADE)
                .setParameter("limite", MAX_CANDIDATOS)
                .getResultList();

        return produtos.stream().map(this::mapToDTO).toList();
    }

    private String removePalavrasDeEnchimento(String texto) {
        String semAcento = Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return Arrays.stream(semAcento.split("[^a-z0-9]+"))
                .filter(palavra -> !palavra.isEmpty() && !PALAVRAS_DE_ENCHIMENTO.contains(palavra))
                .collect(Collectors.joining(" "));
    }

    private ProdutoDTO mapToDTO(ProdutoEntity entity) {
        return ProdutoDTO.builder()
                .codigoBarras(entity.getCodigoBarras())
                .descricao(entity.getDescricao())
                .precoCentavos(entity.getPrecoCentavos())
                .localizacao(mapLocalizacao(entity.getLayoutPosicao()))
                .preListaItemId(entity.getPreListaItemId())
                .build();
    }

    private LocalizacaoDTO mapLocalizacao(LayoutPosicaoEntity layoutPosicao) {
        if (layoutPosicao == null) {
            return null;
        }

        return LocalizacaoDTO.builder()
                .nomeSetor(layoutPosicao.getNomeSetor())
                .rua(layoutPosicao.getRua())
                .quarteirao(layoutPosicao.getQuarteirao())
                .lado(layoutPosicao.getLado())
                .build();
    }
}
