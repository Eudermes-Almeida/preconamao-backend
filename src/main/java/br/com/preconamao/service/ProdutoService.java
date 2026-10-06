package br.com.preconamao.service;

import br.com.preconamao.dto.LocalizacaoDTO;
import br.com.preconamao.dto.ProdutoDTO;
import br.com.preconamao.entity.LayoutPosicaoEntity;
import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.entity.ProdutoEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

// Consultas do app, sempre na loja pedida (produto é da loja — regra 1 do multi-loja).
@ApplicationScoped
public class ProdutoService {

    // Com ~6 mil produtos, "absorvente" acha 28: o app mostra os 10 mais parecidos e, havendo
    // mais, pede para o cliente falar também a marca.
    private static final int MAX_CANDIDATOS = 10;
    private static final double MARGEM_DO_MELHOR = 0.05;

    // Abaixo disso a similaridade é ruído (ex.: "creme dental" x "cremoso" dá ~0,31).
    private static final float LIMIAR_SIMILARIDADE = 0.4f;

    // Validade das promoções conta no dia do Brasil (servidor em UTC).
    private static final ZoneId FUSO_DA_LOJA = ZoneId.of("America/Sao_Paulo");

    // Palavras de enchimento típicas de quem fala ("quero o veja", "qual o preço do ..."):
    // sem removê-las, cada uma dilui a similaridade com a descrição do produto.
    private static final Set<String> PALAVRAS_DE_ENCHIMENTO = Set.of(
            "quero", "queria", "qual", "quanto", "custa", "preco", "produto", "por", "favor",
            "me", "mostra", "mostre", "ver", "consultar",
            "o", "a", "os", "as", "um", "uma", "de", "do", "da", "dos", "das", "e");

    @Inject
    EntityManager entityManager;

    @Inject
    LojaService lojaService;

    // Limite de códigos por chamada do lote (ofertas do app e revalidação do carrinho).
    public static final int MAX_LOTE = 100;

    // O que vale para todos os produtos de uma consulta: a loja, a proteção de preço dela e o formato.
    private record Contexto(LojaEntity loja, LojaService.SituacaoPreco situacao, FormatoPricetab formato, LocalDate hoje) {
    }

    private Contexto contexto(LojaEntity loja) {
        return new Contexto(loja, lojaService.situacaoPreco(loja), FormatoPricetab.de(lojaService.formato(loja)),
                LocalDate.now(FUSO_DA_LOJA));
    }

    // Código exato primeiro (inclui os EAN "2..." de uso interno que não são de balança); só sem
    // resultado é que o código é tratado como etiqueta de balança da loja.
    @Transactional
    public Optional<ProdutoDTO> buscaPorCodigoBarras(String codigoBarras, LojaEntity loja) {
        return buscaPorCodigoBarras(codigoBarras, contexto(loja));
    }

    // Ofertas do app e revalidação do carrinho: os códigos não encontrados (ou inativos) ficam de
    // fora da lista.
    @Transactional
    public List<ProdutoDTO> buscaPorCodigos(List<String> codigos, LojaEntity loja) {
        Contexto contexto = contexto(loja);
        return codigos.stream()
                .map(String::trim)
                .filter(codigo -> !codigo.isEmpty())
                .distinct()
                .limit(MAX_LOTE)
                .map(codigo -> buscaPorCodigoBarras(codigo, contexto))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<ProdutoDTO> buscaPorCodigoBarras(String codigoBarras, Contexto contexto) {
        String codigoLido = codigoBarras.trim();

        Optional<ProdutoDTO> exato = buscaEntidade(CodigoBarras.canonico(codigoLido), contexto.loja())
                .map(produto -> mapToDTO(produto, contexto));
        if (exato.isPresent()) {
            return exato;
        }
        // Todo produto com código interno pode ser achado pela etiqueta (regra 20a), vendido por
        // quilo ou não (alface, pizza e frios fatiados também saem da balança).
        return EtiquetaBalanca.decodificar(codigoLido, contexto.loja())
                .flatMap(leitura -> buscaPorCodigoBalanca(leitura.codigoProduto(), contexto.loja())
                        .map(produto -> mapEtiquetaToDTO(produto, codigoLido, leitura.valorCentavos(), contexto)));
    }

    // A etiqueta traz o código interno (sem zeros à frente e, se a loja usa, sem o dígito
    // verificador): é o codigo_balanca calculado na carga.
    private Optional<ProdutoEntity> buscaPorCodigoBalanca(String codigoEtiqueta, LojaEntity loja) {
        return entityManager.createQuery(
                        "SELECT p FROM ProdutoEntity p LEFT JOIN FETCH p.layoutPosicao "
                                + "WHERE p.lojaId = :loja AND p.codigoBalanca = :codigo AND p.ativo = true ORDER BY p.codigoBarras",
                        ProdutoEntity.class)
                .setParameter("loja", loja.getId())
                .setParameter("codigo", codigoEtiqueta)
                .setMaxResults(1)
                .getResultStream().findFirst();
    }

    private Optional<ProdutoEntity> buscaEntidade(String codigoBarras, LojaEntity loja) {
        // LEFT JOIN FETCH: traz a localização (se houver) na mesma consulta, em vez de uma
        // segunda ida ao banco só para o lazy load de layoutPosicao.
        return entityManager.createQuery(
                        "SELECT p FROM ProdutoEntity p LEFT JOIN FETCH p.layoutPosicao "
                                + "WHERE p.lojaId = :loja AND p.codigoBarras = :codigoBarras AND p.ativo = true",
                        ProdutoEntity.class)
                .setParameter("loja", loja.getId())
                .setParameter("codigoBarras", codigoBarras)
                .getResultStream().findFirst();
    }

    // Busca por descrição falada. JPQL não conhece pg_trgm, então estas são consultas nativas.
    // Filtro: word_similarity do texto falado inteiro com a descrição expandida já normalizada
    // (descricao_busca, com índice de trigramas: o operador <% usa o índice e o limiar vem de
    // pg_trgm.word_similarity_threshold, só nesta transação). Nota: a MÉDIA, palavra por palavra,
    // da semelhança de cada palavra falada com o trecho mais parecido da descrição — tolera o erro
    // do reconhecimento de voz numa palavra só ("macarrão nissan" separa os 15 NISSIN, nota 0,79,
    // dos outros macarrões, 0,57; com o texto inteiro, todos empatavam). Códigos auxiliares do
    // mesmo produto (mesmo grupo_codigo) aparecem uma vez só — na loja de descrição cortada cada
    // código é o seu próprio grupo (regra 22a). Empate (comum: "iogurte" está inteiro em "BOLO
    // IOGURTE kg" e em "IOGURTE BATAVO..."): primeiro a descrição que começa com o que foi falado
    // (o tipo do produto vem na frente), depois a mais parecida no todo.
    // Ofertas da vitrine (códigos que o app manda em "destaques") vêm ANTES dos demais, desde que
    // estejam entre os mais parecidos (nota até MARGEM_DO_MELHOR abaixo da melhor): "refrigerante"
    // traz a Coca-Cola em oferta primeiro, mas uma oferta só vagamente parecida não sobe. Com uma
    // palavra só, a oferta também precisa COMEÇAR com ela (ser o tipo pedido): "leite" não sobe o
    // doce de leite em oferta; "doce de leite" sobe. A prioridade entra antes do LIMIT, para a
    // oferta não ficar de fora dos 10.
    private static final String NOTA = "(SELECT avg(word_similarity(w, q.descricao_busca)) "
            + "FROM unnest(CAST(:palavras AS text[])) w)";

    public record ResultadoBusca(List<ProdutoDTO> produtos, long totalEncontrado) {
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public ResultadoBusca buscaPorDescricao(String descricao, List<String> destaques, LojaEntity loja) {
        String textoTratado = removePalavrasDeEnchimento(descricao);

        if (textoTratado.isEmpty()) {
            return new ResultadoBusca(List.of(), 0);
        }

        entityManager.createNativeQuery("SELECT set_config('pg_trgm.word_similarity_threshold', :limiar, true)")
                .setParameter("limiar", String.valueOf(LIMIAR_SIMILARIDADE))
                .getSingleResult();

        // Só letras e dígitos (ver removePalavrasDeEnchimento): seguro dentro do literal de array.
        String palavras = "{" + textoTratado.replace(' ', ',') + "}";
        // Idem: só códigos numéricos (o resto é descartado), já no formato canônico.
        String codigosDestaque = "{" + destaques.stream().map(String::trim)
                .filter(codigo -> codigo.matches("\\d{1,14}")).map(CodigoBarras::canonico).distinct().limit(MAX_LOTE)
                .collect(Collectors.joining(",")) + "}";

        List<ProdutoEntity> produtos = entityManager.createNativeQuery(
                        "SELECT p.* FROM produtos p JOIN ("
                                + "  SELECT DISTINCT ON (a.grupo) a.id, a.similaridade, a.oferta FROM ("
                                + "    SELECT q.id, q.codigo_barras, coalesce(q.grupo_codigo, q.codigo_barras) AS grupo, "
                                + "           " + NOTA + " AS similaridade, "
                                + "           coalesce(q.grupo_codigo, q.codigo_barras) IN ("
                                + "             SELECT coalesce(d.grupo_codigo, d.codigo_barras) FROM produtos d "
                                + "              WHERE d.loja_id = :loja AND d.ativo AND d.codigo_barras = ANY (CAST(:destaques AS text[]))) AS oferta "
                                + "      FROM produtos q WHERE q.loja_id = :loja AND q.ativo AND :texto <% q.descricao_busca) a "
                                + "  ORDER BY a.grupo, a.similaridade DESC, (a.codigo_barras = a.grupo) DESC) m ON m.id = p.id "
                                + "ORDER BY (m.oferta AND m.similaridade >= max(m.similaridade) OVER () - :margem "
                                + "          AND (:variasPalavras OR starts_with(p.descricao_busca, :texto))) DESC, "
                                + "m.similaridade DESC, starts_with(p.descricao_busca, :texto) DESC, "
                                + "similarity(:texto, p.descricao_busca) DESC, coalesce(p.descricao_expandida, p.descricao), p.preco_centavos "
                                + "LIMIT :limite",
                        ProdutoEntity.class)
                .setParameter("loja", loja.getId())
                .setParameter("texto", textoTratado)
                .setParameter("palavras", palavras)
                .setParameter("destaques", codigosDestaque)
                .setParameter("variasPalavras", textoTratado.contains(" "))
                .setParameter("margem", MARGEM_DO_MELHOR)
                .setParameter("limite", MAX_CANDIDATOS)
                .getResultList();

        // Quantos produtos (grupos) são TÃO parecidos quanto o melhor (nota até 0,05 abaixo dele):
        // "absorvente" = os 31 que têm a palavra; "absorvente intimus noturno" = só o que bate com
        // tudo; "macarrão nissan" = os 15 Nissin (senão o app pediria a marca a quem já falou).
        // Só conta quando a lista veio cheia.
        long total = produtos.size();
        if (produtos.size() == MAX_CANDIDATOS) {
            // A melhor nota entre os produtos da lista (o 1º pode ser uma oferta, com nota um pouco menor).
            double melhor = ((Number) entityManager.createNativeQuery(
                            "SELECT max(" + NOTA + ") FROM produtos q WHERE q.id = ANY (CAST(:ids AS bigint[]))")
                    .setParameter("palavras", palavras)
                    .setParameter("ids", produtos.stream().map(produto -> String.valueOf(produto.getId()))
                            .collect(Collectors.joining(",", "{", "}")))
                    .getSingleResult()).doubleValue();
            total = ((Number) entityManager.createNativeQuery(
                            "SELECT count(DISTINCT x.grupo) FROM ("
                                    + "  SELECT coalesce(q.grupo_codigo, q.codigo_barras) AS grupo, "
                                    + "         " + NOTA + " AS similaridade "
                                    + "    FROM produtos q WHERE q.loja_id = :loja AND q.ativo AND :texto <% q.descricao_busca) x "
                                    + "WHERE x.similaridade >= :melhor - :margem")
                    .setParameter("loja", loja.getId())
                    .setParameter("texto", textoTratado)
                    .setParameter("palavras", palavras)
                    .setParameter("melhor", melhor)
                    .setParameter("margem", MARGEM_DO_MELHOR)
                    .getSingleResult()).longValue();
        }

        Contexto contexto = contexto(loja);
        return new ResultadoBusca(produtos.stream().map(produto -> mapToDTO(produto, contexto)).toList(), total);
    }

    private String removePalavrasDeEnchimento(String texto) {
        String semAcento = Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return Arrays.stream(semAcento.split("[^a-z0-9]+"))
                .filter(palavra -> !palavra.isEmpty() && !PALAVRAS_DE_ENCHIMENTO.contains(palavra))
                .collect(Collectors.joining(" "));
    }

    private ProdutoDTO mapToDTO(ProdutoEntity entity, Contexto contexto) {
        String exibida = descricaoExibida(entity);
        // PRICETAB de 16 posições: descrição do tamanho do campo provavelmente foi cortada (regra 22c).
        boolean cortada = contexto.formato().descricaoCortada() && entity.getDescricao().strip().length() >= 16;
        boolean promocao = promocaoValida(entity, contexto.hoje());

        // Sem preço por causa do dado (0,00 ou conflito) ou pela proteção da loja (regras 11g, 18, 19).
        String motivoSemPreco = entity.getSemPreco() != null ? entity.getSemPreco()
                : contexto.situacao().confiavel() ? null : "PROTECAO";

        return ProdutoDTO.builder()
                .codigoBarras(entity.getCodigoBarras())
                .descricao(cortada ? exibida + "…" : exibida)
                .descricaoOriginal(entity.getDescricao().equals(exibida) && !cortada ? null : entity.getDescricao())
                .descricaoCortada(cortada)
                .precoCentavos(promocao ? entity.getPromocaoCentavos() : entity.getPrecoCentavos())
                .precoNormalCentavos(promocao ? entity.getPrecoCentavos() : null)
                .promocaoAte(promocao ? entity.getPromocaoFim().toString() : null)
                .atacadoCentavos(entity.getAtacadoCentavos())
                .atacadoQuantidade(entity.getAtacadoQuantidade())
                .condicao(entity.getCondicao())
                .localizacao(mapLocalizacao(entity.getLayoutPosicao()))
                .preListaItemId(entity.getPreListaItemId())
                .vendidoPorKg(entity.isVendidoPorKg())
                .precoKgCentavos(entity.isVendidoPorKg() ? entity.getPrecoCentavos() : null)
                .precoBalancaIndefinido(entity.getCodigoBalanca() != null && !entity.isVendidoPorKg()
                        && entity.getUnidade() == null && !contexto.formato().internoSemKgEhUnidade())
                .precoConfiavel(motivoSemPreco == null)
                .motivoSemPreco(motivoSemPreco)
                .precoConferidoEm(motivoSemPreco == null && contexto.situacao().conferidoEm() != null
                        ? contexto.situacao().conferidoEm().toString() : null)
                .build();
    }

    // Promoção da loja (só vem da API): vale de promocao_inicio até promocao_fim, inclusive; preço
    // promocional maior ou igual ao normal não é promoção.
    private static boolean promocaoValida(ProdutoEntity entity, LocalDate hoje) {
        return entity.getPromocaoCentavos() != null && entity.getPromocaoCentavos() > 0
                && entity.getPromocaoCentavos() < entity.getPrecoCentavos()
                && entity.getPromocaoFim() != null && !hoje.isAfter(entity.getPromocaoFim())
                && (entity.getPromocaoInicio() == null || !hoje.isBefore(entity.getPromocaoInicio()));
    }

    // A expandida, quando existe (produto anterior ao script 025 ou ainda sem carga: a original).
    private static String descricaoExibida(ProdutoEntity entity) {
        String expandida = entity.getDescricaoExpandida();
        return expandida == null || expandida.isBlank() ? entity.getDescricao() : expandida;
    }

    // Cada etiqueta vira um "produto" próprio: código da etiqueta (dois pacotes com o mesmo
    // valor somam quantidade no carrinho, pacotes diferentes ficam em linhas separadas) e preço
    // = total impresso, que é o que o caixa cobra. Vale mesmo com a loja sem sinal ou o produto
    // sem preço no cadastro: o valor vem impresso.
    private ProdutoDTO mapEtiquetaToDTO(ProdutoEntity entity, String codigoEtiqueta, int valorCentavos, Contexto contexto) {
        ProdutoDTO dto = mapToDTO(entity, contexto);
        dto.setCodigoBarras(codigoEtiqueta);
        dto.setPrecoCentavos(valorCentavos);
        dto.setPrecoNormalCentavos(null);
        dto.setPromocaoAte(null);
        dto.setEtiquetaBalanca(true);
        dto.setPrecoConfiavel(true);
        dto.setMotivoSemPreco(null);
        dto.setPrecoBalancaIndefinido(false);
        if (!entity.isVendidoPorKg()) {
            dto.setPrecoKgCentavos(null);
        }
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
