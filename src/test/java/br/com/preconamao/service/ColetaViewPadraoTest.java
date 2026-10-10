package br.com.preconamao.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Conversão da linha da VIEW padrão do conector de banco (vw_simplifica_precos) para o formato
// padrão da carga, como o agente manda (valores do banco já em texto: números com ponto, datas
// "aaaa-mm-dd hh:mm:ss"), sem banco e sem rede. Inclui as VIEWs com só as colunas obrigatórias.
class ColetaViewPadraoTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 9);

    private static Map<String, Object> linha(String codigoBarras, String descricao, Object preco) {
        Map<String, Object> l = new HashMap<>();
        l.put("codigo_barras", codigoBarras);
        l.put("descricao", descricao);
        l.put("preco", preco);
        return l;
    }

    private static Map<String, Map<String, Object>> converter(Map<String, Object>... linhas) {
        Map<String, Map<String, Object>> porCodigo = new LinkedHashMap<>();
        ColetaRpinfo.Contagem contagem = new ColetaRpinfo.Contagem();
        for (Map<String, Object> l : linhas) {
            ColetaViewPadrao.converter(l, HOJE, porCodigo, contagem);
        }
        return porCodigo;
    }

    @Test
    void viewSoComAsTresObrigatorias() {
        Map<String, Object> item = converter(linha("7894900011517", "REFRIG COCA COLA 2L", "9.99")).get("7894900011517");
        assertEquals(999, item.get("preco"));
        assertEquals("REFRIG COCA COLA 2L", item.get("descricao"));
        assertNull(item.get("unidade"));
        assertNull(item.get("secao"));
        assertNull(item.get("promocao"));
        // Sem codigo_interno: o próprio código de barras identifica o produto.
        assertEquals("7894900011517", item.get("codigoInterno"));
    }

    @Test
    void codigoInternoAgrupaOsCodigosDoProduto() {
        Map<String, Object> a = linha("7791293008868", "DESOD AERO DOVE 150ml", "17.99");
        Map<String, Object> b = linha("7791293022819", "DESOD AERO DOVE 150ml", "17.99");
        a.put("codigo_interno", "1816");
        b.put("codigo_interno", " 1816 ");
        Map<String, Map<String, Object>> itens = converter(a, b);
        assertEquals("1816", itens.get("7791293008868").get("codigoInterno"));
        assertEquals("1816", itens.get("7791293022819").get("codigoInterno"));
    }

    @Test
    void codigoInternoVazioCaiNoCodigoDeBarras() {
        Map<String, Object> l = linha("7891000100103", "LEITE MOCA 395G", "8.49");
        l.put("codigo_interno", "  ");
        assertEquals("7891000100103", converter(l).get("7891000100103").get("codigoInterno"));
        assertEquals("7891000100103", ColetaViewPadrao.interno(l));
    }

    @Test
    void ofertaVigenteValeVencidaOuMaiorNao() {
        Map<String, Object> vigente = linha("7890000000024", "AÇÚCAR REFINADO UNIÃO 1kg", "5.49");
        vigente.put("preco_promocional", "4.94");
        vigente.put("promocao_ate", "2026-10-16 00:00:00");
        Map<String, Object> vencida = linha("7890000000048", "CAFÉ PILÃO 500g", "21.99");
        vencida.put("preco_promocional", "19.79");
        vencida.put("promocao_ate", "2026-10-08");
        Map<String, Object> maior = linha("7890000000062", "PURÊ DE BATATA MAGGI 51g", "3.89");
        maior.put("preco_promocional", "4.50");
        maior.put("promocao_ate", "16/10/2026");
        Map<String, Map<String, Object>> itens = converter(vigente, vencida, maior);
        assertEquals(494, itens.get("7890000000024").get("promocao"));
        assertEquals("2026-10-16", itens.get("7890000000024").get("promocaoFim"));
        assertNull(itens.get("7890000000048").get("promocao"));
        assertNull(itens.get("7890000000062").get("promocao"));
    }

    @Test
    void ofertaNoUltimoDiaAindaVale() {
        Map<String, Object> l = linha("7890000000079", "FEIJÃO CARIOCA 1kg", "8.99");
        l.put("preco_promocional", "7.99");
        l.put("promocao_ate", "2026-10-09");
        assertEquals(799, converter(l).get("7890000000079").get("promocao"));
    }

    @Test
    void inativoNaoEntraEmNenhumFormato() {
        for (Object ativo : new Object[]{"N", "n", "0", "NÃO", "false", "F", 0, Boolean.FALSE}) {
            Map<String, Object> l = linha("7890000000086", "CHÁ MATE LEÃO 250g", "10.99");
            l.put("ativo", ativo);
            assertTrue(converter(l).isEmpty(), "ativo = " + ativo);
        }
        for (Object ativo : new Object[]{"S", "1", 1, Boolean.TRUE, null}) {
            Map<String, Object> l = linha("7890000000086", "CHÁ MATE LEÃO 250g", "10.99");
            l.put("ativo", ativo);
            assertFalse(converter(l).isEmpty(), "ativo = " + ativo);
        }
    }

    @Test
    void unidadeKgOuUnOuDesconhecida() {
        Map<String, Object> kg = linha("7890000000017", "PÃO FRANCÊS kg", "16.99");
        kg.put("unidade", " kg ");
        Map<String, Object> un = linha("7890000000031", "MAÇÃ GALA", "12.90");
        un.put("unidade", "UN");
        Map<String, Object> outra = linha("7890000000055", "LIMÃO TAHITI", "4.99");
        outra.put("unidade", "PC");
        Map<String, Map<String, Object>> itens = converter(kg, un, outra);
        assertEquals("KG", itens.get("7890000000017").get("unidade"));
        assertEquals("UN", itens.get("7890000000031").get("unidade"));
        assertNull(itens.get("7890000000055").get("unidade"));
    }

    @Test
    void acentosEAspasPassamIntactos() {
        String descricao = "SALSICHA HOT-DOG SADIA \"TRADIÇÃO\" D'OURO 500g";
        Map<String, Object> l = linha("7890000000093", descricao, "12.49");
        l.put("secao", "FRIOS E LATICÍNIOS");
        Map<String, Object> item = converter(l).get("7890000000093");
        assertEquals(descricao, item.get("descricao"));
        assertEquals("FRIOS E LATICÍNIOS", item.get("secao"));
    }

    @Test
    void precoComVirgulaOuNumero() {
        assertEquals(1299, converter(linha("7890000000109", "BISCOITO", "12,99")).get("7890000000109").get("preco"));
        assertEquals(1299, converter(linha("7890000000109", "BISCOITO", 12.99)).get("7890000000109").get("preco"));
        assertEquals(1299, converter(linha("7890000000109", "BISCOITO", new java.math.BigDecimal("12.990"))).get("7890000000109").get("preco"));
    }

    @Test
    void linhaInvalidaNaoEntra() {
        assertTrue(converter(linha("7890000000116", "", "1.00")).isEmpty(), "sem descrição");
        assertTrue(converter(linha("7890000000116", "X", null)).isEmpty(), "sem preço");
        assertTrue(converter(linha("7890000000116", "X", "-1")).isEmpty(), "preço negativo");
        assertTrue(converter(linha("78900A0000116", "X", "1.00")).isEmpty(), "código com letra");
        assertTrue(converter(linha("123456789012345", "X", "1.00")).isEmpty(), "código com 15 dígitos");
    }

    @Test
    void espacosRepetidosNaDescricaoViramUm() {
        assertEquals("ARROZ TIO JOAO 5kg",
                converter(linha("7893500018018", "  ARROZ   TIO JOAO  5kg ", "29.90")).get("7893500018018").get("descricao"));
    }
}
