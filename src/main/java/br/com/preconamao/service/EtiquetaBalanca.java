package br.com.preconamao.service;

import br.com.preconamao.entity.LojaEntity;

import java.util.Optional;

// Decodifica a etiqueta impressa pela balança da loja (hortifrúti, açougue, padaria). O código
// não está no PRICETAB: é gerado na pesagem, no padrão EAN-13 de uso interno (começa com 2).
// O layout é de cada LOJA (regra 20 do multi-loja; colunas etiqueta_* da tabela loja). Exemplo
// real enviado pelo técnico (6 dígitos de código, sem dígito verificador no interno):
//   2 | 000266 | 00680 | 6        (CARNE BOVINA PATINHO, 0,170 kg x R$ 39,98 = R$ 6,80)
//   prefixo | código do produto | valor total em centavos | dígito
// Índices começam em 0.
public final class EtiquetaBalanca {

    private EtiquetaBalanca() {
    }

    // codigoProduto vem sem os zeros da frente (compara com produtos.codigo_balanca).
    public record Leitura(String codigoProduto, int valorCentavos) {
    }

    // Vazio quando o código não é etiqueta de balança desta loja ou o dígito verificador não
    // confere (leitura errada da câmera: melhor "não encontrado" do que um preço trocado).
    public static Optional<Leitura> decodificar(String codigoBarras, LojaEntity loja) {
        if (codigoBarras.length() != 13 || !codigoBarras.chars().allMatch(Character::isDigit)
                || !codigoBarras.startsWith(loja.getEtiquetaPrefixo()) || !digitoVerificadorConfere(codigoBarras)) {
            return Optional.empty();
        }
        int codigoInicio = loja.getEtiquetaCodigoInicio();
        int valorInicio = loja.getEtiquetaValorInicio();
        String codigoProduto = codigoBarras.substring(codigoInicio, codigoInicio + loja.getEtiquetaCodigoTamanho())
                .replaceFirst("^0+(?=.)", "");
        int valorCentavos = Integer.parseInt(codigoBarras.substring(valorInicio, valorInicio + loja.getEtiquetaValorTamanho()));
        return Optional.of(new Leitura(codigoProduto, valorCentavos));
    }

    // EAN-13: pesos 1 e 3 alternados nos 12 primeiros dígitos; o 13º completa a soma até a
    // próxima dezena.
    static boolean digitoVerificadorConfere(String codigoBarras) {
        int soma = 0;
        for (int i = 0; i < 12; i++) {
            int digito = codigoBarras.charAt(i) - '0';
            soma += (i % 2 == 0) ? digito : digito * 3;
        }
        int esperado = (10 - soma % 10) % 10;
        return esperado == codigoBarras.charAt(12) - '0';
    }
}
