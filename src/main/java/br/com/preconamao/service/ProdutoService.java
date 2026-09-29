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

    @Inject
    EtiquetaBalanca etiquetaBalanca;

    @Inject
    LojaService lojaService;

    // Limite de códigos por chamada do lote (ofertas do app e revalidação do carrinho).
    public static final int MAX_LOTE = 100;

    // Código exato primeiro (inclui os EAN "2..." de uso interno que não são de balança); só sem
    // resultado é que o código é tratado como etiqueta de balança.
    @Transactional
    public Optional<ProdutoDTO> buscaPorCodigoBarras(String codigoBarras) {
        return buscaPorCodigoBarras(codigoBarras, lojaService.situacaoPreco());
    }

    // Ofertas do app e revalidação do carrinho: os códigos não encontrados (ou inativos) ficam de
    // fora da lista.
    @Transactional
    public List<ProdutoDTO> buscaPorCodigos(List<String> codigos) {
        LojaService.SituacaoPreco situacao = lojaService.situacaoPreco();
        return codigos.stream()
                .map(String::trim)
                .filter(codigo -> !codigo.isEmpty())
                .distinct()
                .limit(MAX_LOTE)
                .map(codigo -> buscaPorCodigoBarras(codigo, situacao))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<ProdutoDTO> buscaPorCodigoBarras(String codigoBarras, LojaService.SituacaoPreco situacao) {
        String codigoTratado = codigoBarras.trim();

        Optional<ProdutoDTO> exato = buscaEntidade(codigoTratado).map(produto -> mapToDTO(produto, situacao));
        if (exato.isPresent()) {
            return exato;
        }
        return etiquetaBalanca.decodificar(codigoTratado)
                .flatMap(leitura -> buscaEntidade(leitura.codigoProduto())
                        .filter(ProdutoEntity::isVendidoPorKg)
                        .map(produto -> mapEtiquetaToDTO(produto, codigoTratado, leitura.valorCentavos())));
    }

    private Optional<ProdutoEntity> buscaEntidade(String codigoBarras) {
        try {
            // LEFT JOIN FETCH: traz a localização (se houver) na mesma consulta, em vez de uma
            // segunda ida ao banco só para o lazy load de layoutPosicao.
            return Optional.of(entityManager.createQuery(
                            "SELECT p FROM ProdutoEntity p LEFT JOIN FETCH p.layoutPosicao "
                                    + "WHERE p.codigoBarras = :codigoBarras AND p.ativo = true",
                            ProdutoEntity.class)
                    .setParameter("codigoBarras", codigoBarras)
                    .getSingleResult());
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
                                + "WHERE p.ativo AND word_similarity(unaccent(:texto), unaccent(lower(p.descricao))) >= :limiar "
                                + "ORDER BY word_similarity(unaccent(:texto), unaccent(lower(p.descricao))) DESC, p.descricao "
                                + "LIMIT :limite",
                        ProdutoEntity.class)
                .setParameter("texto", textoTratado)
                .setParameter("limiar", LIMIAR_SIMILARIDADE)
                .setParameter("limite", MAX_CANDIDATOS)
                .getResultList();

        LojaService.SituacaoPreco situacao = lojaService.situacaoPreco();
        return produtos.stream().map(produto -> mapToDTO(produto, situacao)).toList();
    }

    private String removePalavrasDeEnchimento(String texto) {
        String semAcento = Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return Arrays.stream(semAcento.split("[^a-z0-9]+"))
                .filter(palavra -> !palavra.isEmpty() && !PALAVRAS_DE_ENCHIMENTO.contains(palavra))
                .collect(Collectors.joining(" "));
    }

    private ProdutoDTO mapToDTO(ProdutoEntity entity, LojaService.SituacaoPreco situacao) {
        return ProdutoDTO.builder()
                .codigoBarras(entity.getCodigoBarras())
                .descricao(entity.getDescricao())
                .precoCentavos(entity.getPrecoCentavos())
                .localizacao(mapLocalizacao(entity.getLayoutPosicao()))
                .preListaItemId(entity.getPreListaItemId())
                .vendidoPorKg(entity.isVendidoPorKg())
                .precoKgCentavos(entity.isVendidoPorKg() ? entity.getPrecoCentavos() : null)
                .precoConfiavel(situacao.confiavel())
                .precoConferidoEm(situacao.conferidoEm() == null ? null : situacao.conferidoEm().toString())
                .build();
    }

    // Cada etiqueta vira um "produto" próprio: código da etiqueta (dois pacotes com o mesmo
    // valor somam quantidade no carrinho, pacotes diferentes ficam em linhas separadas) e preço
    // = total impresso, que é o que o caixa cobra.
    private ProdutoDTO mapEtiquetaToDTO(ProdutoEntity entity, String codigoEtiqueta, int valorCentavos) {
        // O valor vem impresso na etiqueta: vale mesmo com a loja sem sinal de vida.
        ProdutoDTO dto = mapToDTO(entity, new LojaService.SituacaoPreco(true, null));
        dto.setCodigoBarras(codigoEtiqueta);
        dto.setPrecoCentavos(valorCentavos);
        dto.setEtiquetaBalanca(true);
        return dto;
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
