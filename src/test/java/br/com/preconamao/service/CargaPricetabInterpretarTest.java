package br.com.preconamao.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Leitura do PRICETAB nos dois formatos (simulado da Gertec e o real do PRICE2.TXT), sem banco.
class CargaPricetabInterpretarTest {

    private final CargaPricetabService service = new CargaPricetabService();

    private CargaPricetabService.Interpretacao interpretar(String texto) {
        return service.interpretar(texto.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void formatoSimuladoEmCentavos() {
        var resultado = interpretar("7891024037973|CREME DENTAL COLGATE 180G               |0000000389||\r\n");
        assertEquals(1, resultado.itens().size());
        assertEquals(new CargaPricetabService.Item("7891024037973", "CREME DENTAL COLGATE 180G", 389), resultado.itens().get(0));
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
}
