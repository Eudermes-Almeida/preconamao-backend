package br.com.preconamao.service;

import br.com.preconamao.dto.ProdutoDTO;
import br.com.preconamao.entity.LojaEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// Campanhas de mídia de uma loja (regra 4a do multi-loja; tabelas campanha e campanha_loja).
// Vale a campanha ativa, dentro da validade e com alcance que inclui a loja (todas, a rede dela,
// ou ela escolhida). A arte só aparece se o produto existe na loja, ativo e com preço acima de
// zero; vale o primeiro código da lista que a loja tiver, e o preço é o DESTA loja.
@ApplicationScoped
public class CampanhaService {

    private static final ZoneId FUSO_DA_LOJA = ZoneId.of("America/Sao_Paulo");

    @Inject
    EntityManager entityManager;

    @Inject
    ProdutoService produtoService;

    public record CampanhaDaLoja(Integer id, String nome, String imagem, String codigoBarras, ProdutoDTO produto) {
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public List<CampanhaDaLoja> campanhas(LojaEntity loja) {
        List<Object[]> linhas = entityManager.createNativeQuery(
                        "SELECT c.id, c.nome, c.arte, ("
                                + "  SELECT x.codigo FROM unnest(c.codigos) WITH ORDINALITY AS x(codigo, n) "
                                + "    JOIN produtos p ON p.loja_id = :loja AND p.codigo_barras = x.codigo "
                                + "   WHERE p.ativo AND p.preco_centavos > 0 AND p.sem_preco IS NULL "
                                + "   ORDER BY x.n LIMIT 1) AS codigo "
                                + "  FROM campanha c JOIN loja l ON l.id = :loja "
                                + " WHERE c.ativa AND c.inicio <= :hoje AND (c.fim IS NULL OR c.fim >= :hoje) "
                                + "   AND (c.alcance = 'TODAS' OR (c.alcance = 'REDE' AND c.rede_id = l.rede_id) "
                                + "        OR (c.alcance = 'LOJAS' AND EXISTS (SELECT 1 FROM campanha_loja cl "
                                + "                                            WHERE cl.campanha_id = c.id AND cl.loja_id = l.id))) "
                                + " ORDER BY c.ordem, c.id")
                .setParameter("loja", loja.getId())
                .setParameter("hoje", LocalDate.now(FUSO_DA_LOJA))
                .getResultList();
        List<Object[]> comProduto = linhas.stream().filter(l -> l[3] != null).toList();
        Map<String, ProdutoDTO> produtos = produtoService.buscaPorCodigos(
                        comProduto.stream().map(l -> (String) l[3]).toList(), loja).stream()
                .collect(Collectors.toMap(ProdutoDTO::getCodigoBarras, Function.identity(), (a, b) -> a));
        return comProduto.stream()
                .filter(l -> produtos.containsKey((String) l[3]))
                .map(l -> new CampanhaDaLoja(((Number) l[0]).intValue(), (String) l[1], (String) l[2], (String) l[3],
                        produtos.get((String) l[3])))
                .toList();
    }
}
