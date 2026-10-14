package br.com.preconamao.service;

import org.junit.jupiter.api.Test;

import static br.com.preconamao.service.UnidadePeloNome.Unidade.KG;
import static br.com.preconamao.service.UnidadePeloNome.Unidade.UN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Nomes reais do PRICETAB de 16 posições (hipermercado).
class UnidadePeloNomeTest {

    @Test
    void kgCortadoNoFimEhQuilo() {
        assertEquals(KG, UnidadePeloNome.de("HF BANANA MACA K"));
        assertEquals(KG, UnidadePeloNome.de("HF CEBOLA ROXA K"));
        assertEquals(KG, UnidadePeloNome.de("HF TOMATE MACA K "));
    }

    @Test
    void unidadeEBandejaSaoUnidade() {
        assertEquals(UN, UnidadePeloNome.de("HF ABACAXI UN"));
        assertEquals(UN, UnidadePeloNome.de("HF ALFACE LISA U"));
        assertEquals(UN, UnidadePeloNome.de("HF CHUCHU BDJA"));
        assertEquals(UN, UnidadePeloNome.de("HF UVA ROSADA BD"));
        assertEquals(UN, UnidadePeloNome.de("HF LICHIA BDJA E"));
        assertEquals(UN, UnidadePeloNome.de("PAD BOLO COCO UN"));
    }

    @Test
    void semMarcaContinuaIndefinido() {
        assertNull(UnidadePeloNome.de("HF CENOURA VERM"));
        assertNull(UnidadePeloNome.de("HF BANANA PRATA"));
        assertNull(UnidadePeloNome.de("PAD PAO ARTESANA"));
        // Palavras que só começam com as letras das marcas não contam.
        assertNull(UnidadePeloNome.de("HF UVA THOMPSON"));
        assertNull(UnidadePeloNome.de("HF KIWI GOLD"));
        assertNull(UnidadePeloNome.de(null));
    }
}
