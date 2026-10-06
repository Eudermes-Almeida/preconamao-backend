package br.com.preconamao.service;

// Código canônico (regra 17 do multi-loja): o mesmo produto chega como "07891991010153" (14
// dígitos), "7891991010153", "0000078938854" (EAN-8 completado) ou "78938854" (EAN-8 lido pelo
// celular), ou ainda como número que perdeu os zeros (API). Tira os zeros da frente e completa até
// 13 dígitos; 14 dígitos sem zero na frente (código de caixa) fica como está. Vale nos dois lados:
// ao gravar (toda origem) e ao consultar (câmera, leitor, digitado, ofertas, carrinho, Família).
// Mesma regra de codigo_canonico() no banco (scripts/027_multiloja.sql).
public final class CodigoBarras {

    private CodigoBarras() {
    }

    // Não numérico volta como veio (sem espaços nas pontas): quem consulta decide o que fazer.
    public static String canonico(String codigo) {
        if (codigo == null) {
            return null;
        }
        String limpo = codigo.trim();
        if (limpo.isEmpty() || !limpo.chars().allMatch(Character::isDigit)) {
            return limpo;
        }
        String semZeros = limpo.replaceFirst("^0+", "");
        if (semZeros.length() > 13) {
            return semZeros;
        }
        return "0".repeat(13 - semZeros.length()) + semZeros;
    }
}
