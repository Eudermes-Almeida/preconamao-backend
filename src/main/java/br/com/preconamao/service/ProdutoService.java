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

    // Com ~6 mil produtos, "absorvente" acha 28: o app mostra os 10 mais parecidos e, havendo
    // mais, pede para o cliente falar também a marca.
    private static final int MAX_CANDIDATOS = 10;
    private static final double MARGEM_DO_MELHOR = 0.05;

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
                        .or(() -> buscaPorCodigoBalanca(leitura.codigoProduto()))
                        .filter(ProdutoEntity::isVendidoPorKg)
                        .map(produto -> mapEtiquetaToDTO(produto, codigoTratado, leitura.valorCentavos())));
    }

    // PRICETAB real: o produto de balança vem como 0000000CCCCCD e a etiqueta traz só o CCCCC
    // (codigo_balanca, calculado na carga; comparado sem zeros à esquerda).
    private Optional<ProdutoEntity> buscaPorCodigoBalanca(String codigoEtiqueta) {
        String semZeros = codigoEtiqueta.replaceFirst("^0+(?=.)", "");
        return entityManager.createQuery(
                        "SELECT p FROM ProdutoEntity p LEFT JOIN FETCH p.layoutPosicao "
                                + "WHERE p.codigoBalanca = :codigo AND p.ativo = true ORDER BY p.codigoBarras",
                        ProdutoEntity.class)
                .setParameter("codigo", semZeros)
                .setMaxResults(1)
                .getResultStream().findFirst();
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

    // Busca por descrição falada. JPQL não conhece pg_trgm, então estas são consultas nativas.
    // Filtro: word_similarity do texto falado inteiro com a descrição expandida já normalizada
    // (descricao_busca, com índice de trigramas: o operador <% usa o índice e o limiar vem de
    // pg_trgm.word_similarity_threshold, só nesta transação). Nota: a MÉDIA, palavra por palavra,
    // da semelhança de cada palavra falada com o trecho mais parecido da descrição — tolera o erro
    // do reconhecimento de voz numa palavra só ("macarrão nissan" separa os 15 NISSIN, nota 0,79,
    // dos outros macarrões, 0,57; com o texto inteiro, todos empatavam). Códigos auxiliares do
    // mesmo produto (mesmo grupo_codigo) aparecem uma vez só. Empate (comum: "iogurte" está inteiro
    // em "BOLO IOGURTE kg" e em "IOGURTE BATAVO..."): primeiro a descrição que começa com o que foi
    // falado (o tipo do produto vem na frente), depois a mais parecida no todo.
    private static final String NOTA = "(SELECT avg(word_similarity(w, q.descricao_busca)) "
            + "FROM unnest(CAST(:palavras AS text[])) w)";

    public record ResultadoBusca(List<ProdutoDTO> produtos, long totalEncontrado) {
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public ResultadoBusca buscaPorDescricao(String descricao) {
        String textoTratado = removePalavrasDeEnchimento(descricao);

        if (textoTratado.isEmpty()) {
            return new ResultadoBusca(List.of(), 0);
        }

        entityManager.createNativeQuery("SELECT set_config('pg_trgm.word_similarity_threshold', :limiar, true)")
                .setParameter("limiar", String.valueOf(LIMIAR_SIMILARIDADE))
                .getSingleResult();

        // Só letras e dígitos (ver removePalavrasDeEnchimento): seguro dentro do literal de array.
        String palavras = "{" + textoTratado.replace(' ', ',') + "}";

        List<ProdutoEntity> produtos = entityManager.createNativeQuery(
                        "SELECT p.* FROM produtos p JOIN ("
                                + "  SELECT DISTINCT ON (a.grupo) a.id, a.similaridade FROM ("
                                + "    SELECT q.id, q.codigo_barras, coalesce(q.grupo_codigo, q.codigo_barras) AS grupo, "
                                + "           " + NOTA + " AS similaridade "
                                + "      FROM produtos q WHERE q.ativo AND :texto <% q.descricao_busca) a "
                                + "  ORDER BY a.grupo, a.similaridade DESC, (a.codigo_barras = a.grupo) DESC) m ON m.id = p.id "
                                + "ORDER BY m.similaridade DESC, starts_with(p.descricao_busca, :texto) DESC, "
                                + "similarity(:texto, p.descricao_busca) DESC, coalesce(p.descricao_expandida, p.descricao) "
                                + "LIMIT :limite",
                        ProdutoEntity.class)
                .setParameter("texto", textoTratado)
                .setParameter("palavras", palavras)
                .setParameter("limite", MAX_CANDIDATOS)
                .getResultList();

        // Quantos produtos (grupos) são TÃO parecidos quanto o melhor (nota até 0,05 abaixo dele):
        // "absorvente" = os 31 que têm a palavra; "absorvente intimus noturno" = só o que bate com
        // tudo; "macarrão nissan" = os 15 Nissin (senão o app pediria a marca a quem já falou).
        // Só conta quando a lista veio cheia.
        long total = produtos.size();
        if (produtos.size() == MAX_CANDIDATOS) {
            // A melhor nota é a do 1º da lista (ordenada pela nota).
            double melhor = ((Number) entityManager.createNativeQuery(
                            "SELECT " + NOTA + " FROM produtos q WHERE q.id = :id")
                    .setParameter("palavras", palavras)
                    .setParameter("id", produtos.get(0).getId())
                    .getSingleResult()).doubleValue();
            total = ((Number) entityManager.createNativeQuery(
                            "SELECT count(DISTINCT x.grupo) FROM ("
                                    + "  SELECT coalesce(q.grupo_codigo, q.codigo_barras) AS grupo, "
                                    + "         " + NOTA + " AS similaridade "
                                    + "    FROM produtos q WHERE q.ativo AND :texto <% q.descricao_busca) x "
                                    + "WHERE x.similaridade >= :melhor - :margem")
                    .setParameter("texto", textoTratado)
                    .setParameter("palavras", palavras)
                    .setParameter("melhor", melhor)
                    .setParameter("margem", MARGEM_DO_MELHOR)
                    .getSingleResult()).longValue();
        }

        LojaService.SituacaoPreco situacao = lojaService.situacaoPreco();
        return new ResultadoBusca(produtos.stream().map(produto -> mapToDTO(produto, situacao)).toList(), total);
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
                .descricao(descricaoExibida(entity))
                .descricaoOriginal(entity.getDescricao().equals(descricaoExibida(entity)) ? null : entity.getDescricao())
                .precoCentavos(entity.getPrecoCentavos())
                .localizacao(mapLocalizacao(entity.getLayoutPosicao()))
                .preListaItemId(entity.getPreListaItemId())
                .vendidoPorKg(entity.isVendidoPorKg())
                .precoKgCentavos(entity.isVendidoPorKg() ? entity.getPrecoCentavos() : null)
                .precoConfiavel(situacao.confiavel())
                .precoConferidoEm(situacao.conferidoEm() == null ? null : situacao.conferidoEm().toString())
                .build();
    }

    // A expandida, quando existe (produto anterior ao script 025 ou ainda sem carga: a original).
    private static String descricaoExibida(ProdutoEntity entity) {
        String expandida = entity.getDescricaoExpandida();
        return expandida == null || expandida.isBlank() ? entity.getDescricao() : expandida;
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
