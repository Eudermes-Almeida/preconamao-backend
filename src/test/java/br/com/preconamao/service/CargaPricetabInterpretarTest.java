package br.com.preconamao.service;

import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.entity.ProdutoEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Leitura do PRICETAB pela ficha do formato e as regras do multi-loja (17, 18, 19, 7), sem banco.
class CargaPricetabInterpretarTest {

    private final CargaPricetabService service = new CargaPricetabService();

    private static final FormatoPricetab FORMATO_16 = new FormatoPricetab("|", 16, true, true,
            StandardCharsets.ISO_8859_1, false, 500, FormatoPricetab.DIALETO_SIMPLES);

    private CargaPricetabService.Interpretacao interpretar(String texto) {
        return service.interpretar(texto.getBytes(StandardCharsets.ISO_8859_1), FormatoPricetab.PADRAO);
    }

    @Test
    void formatoSimuladoEmCentavos() {
        var resultado = interpretar("7891024037973|CREME DENTAL COLGATE 180G               |0000000389||\r\n");
        assertEquals(1, resultado.itens().size());
        assertEquals(new CargaPricetabService.Item("7891024037973", "7891024037973", "CREME DENTAL COLGATE 180G", 389, null),
                resultado.itens().get(0));
        assertEquals(0, resultado.erros().size());
    }

    @Test
    void formatoRealEmReais() {
        var resultado = interpretar("7898612720194|MORANGO BD 250G                         |12,99|\r\n"
                + "0000000040556|BANANA PRATA kg                         |5,99|\r\n"
                + "17891000100103|LEITE NINHO CX 12UN                    |1.234,56|\r\n");
        assertEquals(3, resultado.itens().size());
        assertEquals(1299, resultado.itens().get(0).preco());
        assertEquals("0000000040556", resultado.itens().get(1).codigo());
        assertEquals(123456, resultado.itens().get(2).preco());
        assertEquals("17891000100103", resultado.itens().get(2).codigo());
    }

    // No arquivo real, algumas descrições vêm cortadas por uma quebra de linha (CRLF ou só LF).
    @Test
    void emendaRegistroQuebrado() {
        var resultado = interpretar("7898979636039|GELO SAB GELAI 220G LAR\r\nANJA           |3,49|\r\n"
                + "7898922719017|MORANGO CONGEL\n MONTE VDE 1,002kg       |19,99|\r\n"
                + "7891097106248|IOG BATAVO GREGO PENSE ZERO\r\n BD 510G   |9,99|\r\n"
                + "7891515962340|LASANHA PERDIGAO 600G FGO BACON         |16,99|\r\n");
        assertEquals(4, resultado.itens().size());
        assertEquals(4, resultado.linhasTotal());
        assertEquals("GELO SAB GELAI 220G LARANJA", resultado.itens().get(0).descricao());
        assertEquals("MORANGO CONGEL MONTE VDE 1,002kg", resultado.itens().get(1).descricao());
        assertEquals("IOG BATAVO GREGO PENSE ZERO BD 510G", resultado.itens().get(2).descricao());
        assertEquals(1699, resultado.itens().get(3).preco());
    }

    @Test
    void linhasInvalidasSaoListadas() {
        var resultado = interpretar("ABC|PRODUTO|1,00|\r\n"
                + "7891|SEM PRECO||\r\n"
                + "7892|PRECO ESTRANHO|1,0|\r\n"
                + "7893||1,00|\r\n");
        assertEquals(0, resultado.itens().size());
        assertEquals(4, resultado.erros().size());
    }

    @Test
    void precoEmCentavos() {
        assertEquals(389, CargaPricetabService.precoEmCentavos("0000000389"));
        assertEquals(1299, CargaPricetabService.precoEmCentavos("12,99"));
        assertEquals(5, CargaPricetabService.precoEmCentavos("0,05"));
        assertEquals(123456, CargaPricetabService.precoEmCentavos("1.234,56"));
        assertNull(CargaPricetabService.precoEmCentavos("12.99"));
        assertNull(CargaPricetabService.precoEmCentavos(""));
    }

    // Regra 17: o mesmo código com 13 e 14 dígitos vira um produto só (e o EAN-8 fica com 13).
    @Test
    void codigoCanonico() {
        assertEquals("7891991010153", CodigoBarras.canonico("07891991010153"));
        assertEquals("0000078938854", CodigoBarras.canonico("78938854"));
        assertEquals("0000000000338", CodigoBarras.canonico("00000000000338"));
        assertEquals("0007891234567", CodigoBarras.canonico("7891234567"));
        assertEquals("17891234567895", CodigoBarras.canonico("17891234567895"));
        var resultado = interpretar("07891991010153|CERV SUB ZERO LT|5,28|\r\n7891991010153|CERV SUB ZERO LT|5,28|\r\n");
        assertEquals(1, resultado.itens().size());
        assertEquals("7891991010153", resultado.itens().get(0).codigo());
        assertEquals(1, resultado.codigosRepetidos());
    }

    // Regras 18 e 19: 0,00 fica sem preço; repetido com um preço e um 0,00 vale o com preço;
    // repetido com preços diferentes fica sem preço (CONFLITO).
    @Test
    void semPrecoERepetidos() {
        var resultado = service.interpretar(("0000000668101|MORTADELA SADIA|0,00|\r\n"
                + "0000078912359|CHOC BATON 16G L|0,00|\r\n"
                + "0000078912359|CHOC BATON 16G L|2,86|\r\n"
                + "7896100502499|SUCO ALIANCA 1L|14,84|\r\n"
                + "7896100502499|SUCO ALIANCA 1L|17,24|\r\n").getBytes(StandardCharsets.ISO_8859_1), FORMATO_16);
        assertEquals(3, resultado.itens().size());
        assertEquals(ProdutoEntity.SEM_PRECO_ZERO, resultado.itens().get(0).semPreco());
        assertNull(resultado.itens().get(1).semPreco());
        assertEquals(286, resultado.itens().get(1).preco());
        assertEquals(ProdutoEntity.SEM_PRECO_CONFLITO, resultado.itens().get(2).semPreco());
        assertEquals(2, resultado.codigosRepetidos());
        assertEquals(1, resultado.conflitosPreco());
        assertEquals(1, resultado.semPrecoZero());
    }

    // Ficha de 16 posições: descrição maior é cortada no tamanho do campo.
    @Test
    void descricaoNoTamanhoDaFicha() {
        var resultado = service.interpretar("7896014123902|CONDIC ELSEVE 200ML|26,44|\r\n".getBytes(StandardCharsets.ISO_8859_1), FORMATO_16);
        assertEquals("CONDIC ELSEVE 20", resultado.itens().get(0).descricao());
    }

    // Regra 7b: a loja vem do nome da cópia enviada pelo agente.
    @Test
    void lojaDoNomeDoArquivo() {
        assertEquals("alfa-centro", CargaPricetabService.lojaDoNome("PRICETAB_alfa-centro_2026-10-06_1430.TXT"));
        assertEquals("beta-hiper", CargaPricetabService.lojaDoNome("pricetab_beta-hiper_2026-10-06_143005.txt"));
        assertNull(CargaPricetabService.lojaDoNome("PRICETAB.TXT"));
        assertNull(CargaPricetabService.lojaDoNome(null));
    }

    // Regra 20: etiqueta pelo layout da loja (exemplo real do técnico: 2 000266 00680 6).
    @Test
    void etiquetaPeloLayoutDaLoja() {
        LojaEntity loja = LojaEntity.builder().etiquetaPrefixo("2").etiquetaCodigoInicio(1).etiquetaCodigoTamanho(6)
                .etiquetaValorInicio(7).etiquetaValorTamanho(5).build();
        var leitura = EtiquetaBalanca.decodificar("2000266006806", loja);
        assertTrue(leitura.isPresent());
        assertEquals("266", leitura.get().codigoProduto());
        assertEquals(680, leitura.get().valorCentavos());
        assertTrue(EtiquetaBalanca.decodificar("2000266006807", loja).isEmpty());
    }

    // Regra 8c: lixo seguido não pode virar "1 linha com erro" pela emenda de linhas quebradas.
    @Test
    void lixoContaPorLinhaDoArquivo() {
        String lixo = "XXXX|LINHA CORROMPIDA|preco?|\r\n".repeat(300);
        var resultado = interpretar(lixo + "7891024037973|CREME DENTAL COLGATE 180G               |3,89|\r\n");
        assertEquals(1, resultado.itens().size());
        assertEquals(301, resultado.linhasFisicas());
        assertEquals(300, resultado.linhasFisicasComErro());
    }
}
