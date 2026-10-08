package br.com.preconamao.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Conversão do produto da RPInfo para o formato padrão da carga, com os casos vistos nas respostas
// reais da homologação (laboratorio/loja5_api_rpinfo/respostas), sem banco e sem rede.
class ColetaRpinfoTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 8);
    private static final Map<String, String> DEPTOS = Map.of("103", "Mercearia Doce", "102", "Mercearia Salgada");

    private static Map<String, Object> produto(String codigoBarras, String descricao, String preco, String precoPdv) {
        Map<String, Object> p = new HashMap<>();
        p.put("Codigo", new BigDecimal("100560"));
        p.put("CodigoBarras", codigoBarras);
        p.put("Descricao", descricao);
        p.put("Preco", new BigDecimal(preco));
        p.put("PrecoPDV", new BigDecimal(precoPdv));
        p.put("PrecoNormal", BigDecimal.ZERO);
        p.put("Oferta", "N");
        p.put("DataOferta", "");
        p.put("DtIniOferta", "");
        p.put("CodigoDepartamento", "103");
        p.put("Departamento", "");
        p.put("Ativo", true);
        p.put("Funcao", "KG");
        p.put("Balanca", "N");
        p.put("codBarrasAlterados", List.of());
        return p;
    }

    private static Map<String, Map<String, Object>> converter(Map<String, Object>... produtos) {
        Map<String, Map<String, Object>> porCodigo = new LinkedHashMap<>();
        ColetaRpinfo.Contagem contagem = new ColetaRpinfo.Contagem();
        for (Map<String, Object> p : produtos) {
            ColetaRpinfo.converter(p, DEPTOS, HOJE, porCodigo, contagem);
        }
        return porCodigo;
    }

    @Test
    void ofertaValidaViraPromocaoComPrecoNormal() {
        Map<String, Object> cafe = produto("7896005806012", "Café Torrado E Moído Itamaraty 500G Pct", "11.98", "11.98");
        cafe.put("Oferta", "S");
        cafe.put("PrecoNormal", new BigDecimal("16.98"));
        cafe.put("DtIniOferta", "01-10-2026");
        cafe.put("DataOferta", "15-10-2026");
        Map<String, Object> item = converter(cafe).get("7896005806012");
        assertEquals(1698, item.get("preco"));
        assertEquals(1198, item.get("promocao"));
        assertEquals("2026-10-01", item.get("promocaoInicio"));
        assertEquals("2026-10-15", item.get("promocaoFim"));
        assertEquals("Mercearia Doce", item.get("secao"));
        assertEquals("UN", item.get("unidade"), "Funcao KG não é venda a peso");
    }

    @Test
    void ofertaVencidaFicaSoComOPrecoDoCaixa() {
        Map<String, Object> cafe = produto("7896005806012", "Café Torrado E Moído Itamaraty 500G Pct", "11.98", "11.98");
        cafe.put("Oferta", "S");
        cafe.put("PrecoNormal", new BigDecimal("16.98"));
        cafe.put("DataOferta", "15-10-2023");
        Map<String, Object> item = converter(cafe).get("7896005806012");
        assertEquals(1198, item.get("preco"));
        assertNull(item.get("promocao"));
    }

    @Test
    void usaPrecoPdvQuandoPrecoDiverge() {
        Map<String, Object> sazon = produto("7891132001132", "Tempero Em Pó Sazón 60G 12 Un Pct", "97.97", "5.97");
        assertEquals(597, converter(sazon).get("7891132001132").get("preco"));
    }

    @Test
    void naoEncontradoNoRpAtomoNaoEntra() {
        assertTrue(converter(produto("7896005806005", "NÃO ENCONTRADO NO RP ÁTOMO", "5.00", "5.00")).isEmpty());
    }

    @Test
    void auxiliaresCaixaEPrecoProprio() {
        Map<String, Object> atum = produto("7896009301131", "Atum Em Conserva Coqueiro 170G", "7.99", "7.99");
        atum.put("codBarrasAlterados", List.of(
                Map.of("codigoBarras", "7894321822020", "fatorEmbalagem", BigDecimal.ZERO, "precoVenda", BigDecimal.ZERO),
                Map.of("codigoBarras", "17896009301138", "fatorEmbalagem", new BigDecimal("12"), "precoVenda", BigDecimal.ZERO),
                Map.of("codigoBarras", "7890000000017", "fatorEmbalagem", new BigDecimal("2"), "precoVenda", new BigDecimal("14.50"))));
        Map<String, Map<String, Object>> itens = converter(atum);
        assertEquals(3, itens.size());
        assertEquals(799, itens.get("7894321822020").get("preco"));
        assertNull(itens.get("17896009301138"), "caixa sem preço próprio não entra");
        assertEquals(1450, itens.get("7890000000017").get("preco"));
    }

    @Test
    void balancaEInternoSemBalanca() {
        Map<String, Object> patinho = produto("0000000000266", "Carne Bovina Patinho", "39.98", "39.98");
        patinho.put("Balanca", "S");
        Map<String, Object> interno = produto("0000000004985", "Banana Prata", "5.99", "5.99");
        Map<String, Map<String, Object>> itens = converter(patinho, interno);
        assertEquals("KG", itens.get("0000000000266").get("unidade"));
        assertNull(itens.get("0000000004985").get("unidade"), "interno sem Balanca: o banco decide pela regra de sempre");
    }

    @Test
    void pesavelVemComoP() {
        // Real (resposta 14, açougue): Balanca "P", código interno com dígito verificador, Funcao "VM;V4;KG".
        Map<String, Object> miolo = produto("0000000002141", "Carne Bov Miolo Alcatra Light Kg", "52.48", "52.48");
        miolo.put("Balanca", "P");
        miolo.put("Funcao", "VM;V4;KG");
        assertEquals("KG", converter(miolo).get("0000000002141").get("unidade"));
    }

    @Test
    void descricaoSemAsteriscoESemCodigoNoFim() {
        assertEquals("Ave Coração Kg Big", ColetaRpinfo.limparDescricao("*ave Coração Kg Big", "133230"));
        assertEquals("Ave File Peito Kg Big", ColetaRpinfo.limparDescricao("Ave File Peito Kg Big 133167", "133167"));
        assertEquals("Ling Cheiro Verde Kg", ColetaRpinfo.limparDescricao("Ling Cheiro Verde   Kg", "139211"));
        assertEquals("Sadia Pacote 1kg Sassami 500", ColetaRpinfo.limparDescricao("Sadia Pacote 1kg Sassami 500", "361259"));
    }

    @Test
    void inativoNaoEntra() {
        Map<String, Object> p = produto("7896009301131", "Atum Em Conserva Coqueiro 170G", "7.99", "7.99");
        p.put("Ativo", false);
        assertTrue(converter(p).isEmpty());
    }

    @Test
    void mensagemDeErroSemStackTrace() {
        // Resposta real da homologação (rota de excluídos com data fora do formato).
        Map<String, Object> envelope = Map.of("status", "error", "messages", List.of(
                Map.of("message", "Falha ao carregar dados"),
                Map.of("message", "Exception: Unparseable date: \" 07-10-2026 00:00:00\""),
                Map.of("message", "StackTrace: java.text.ParseException: ...")));
        assertEquals(": Falha ao carregar dados | Exception: Unparseable date: \" 07-10-2026 00:00:00\"",
                ColetaRpinfo.mensagens(envelope));
        assertEquals("", ColetaRpinfo.mensagens(null));
    }

    @Test
    void motivoNosTresFormatosDeErro() {
        assertEquals(": Não foi possível autenticar o usuário", ColetaRpinfo.motivo(Map.of(
                "error", "Não foi possível autenticar o usuário", "stackTrace", "br.com.rpinfo...ValidationException")));
        assertEquals(": Não autorizado", ColetaRpinfo.motivo(Map.of("appVersion", "5.0.6.117", "content", "Não autorizado")));
        assertEquals(": Produto não encontrado", ColetaRpinfo.motivo(Map.of("response", Map.of("status", "error",
                "messages", List.of(Map.of("message", "Produto não encontrado"))))));
        assertEquals("", ColetaRpinfo.motivo(null));
    }

    @Test
    void dataDaOferta() {
        assertEquals(LocalDate.of(2023, 10, 15), ColetaRpinfo.data("15-10-2023"));
        assertNull(ColetaRpinfo.data(""));
        assertNull(ColetaRpinfo.data("2023-10-15"));
    }
}
