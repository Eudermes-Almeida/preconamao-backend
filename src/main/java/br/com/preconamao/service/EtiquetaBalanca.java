package br.com.preconamao.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

// Decodifica a etiqueta impressa pela balança da loja (hortifrúti, açougue, padaria). O código
// não está no PRICETAB: é gerado na pesagem, no padrão EAN-13 de uso interno (começa com 2).
// Layout levantado das etiquetas reais do Supermercados ABC (ex.: 2298400004652):
//   2 | 2984 | 00 | 00465 | 2
//   prefixo | código interno do produto | (sobra do campo) | valor total em centavos | dígito
// As posições vêm do application.properties (ETIQUETA_BALANCA_*), porque cada rede configura a
// balança do seu jeito. Índices começam em 0.
@ApplicationScoped
public class EtiquetaBalanca {

    @ConfigProperty(name = "etiqueta-balanca.prefixo")
    String prefixo;

    @ConfigProperty(name = "etiqueta-balanca.codigo-inicio")
    int codigoInicio;

    @ConfigProperty(name = "etiqueta-balanca.codigo-tamanho")
    int codigoTamanho;

    @ConfigProperty(name = "etiqueta-balanca.valor-inicio")
    int valorInicio;

    @ConfigProperty(name = "etiqueta-balanca.valor-tamanho")
    int valorTamanho;

    public record Leitura(String codigoProduto, int valorCentavos) {
    }

    // Vazio quando o código não é etiqueta de balança ou o dígito verificador não confere
    // (leitura errada da câmera: melhor "não encontrado" do que um preço trocado).
    public Optional<Leitura> decodificar(String codigoBarras) {
        if (codigoBarras.length() != 13 || !codigoBarras.chars().allMatch(Character::isDigit)
                || !codigoBarras.startsWith(prefixo) || !digitoVerificadorConfere(codigoBarras)) {
            return Optional.empty();
        }

        String codigoProduto = codigoBarras.substring(codigoInicio, codigoInicio + codigoTamanho);
        int valorCentavos = Integer.parseInt(codigoBarras.substring(valorInicio, valorInicio + valorTamanho));
        return Optional.of(new Leitura(codigoProduto, valorCentavos));
    }

    // EAN-13: pesos 1 e 3 alternados nos 12 primeiros dígitos; o 13º completa a soma até a
    // próxima dezena.
    private boolean digitoVerificadorConfere(String codigoBarras) {
        int soma = 0;
        for (int i = 0; i < 12; i++) {
            int digito = codigoBarras.charAt(i) - '0';
            soma += (i % 2 == 0) ? digito : digito * 3;
        }
        int esperado = (10 - soma % 10) % 10;
        return esperado == codigoBarras.charAt(12) - '0';
    }
}
