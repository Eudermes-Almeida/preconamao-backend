package br.com.preconamao.service;

import br.com.preconamao.entity.FormatoOrigemEntity;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

// Parâmetros da ficha do formato (formato_origem.parametros, scripts/027). Ausente = PRICETAB de
// 40 posições, como a loja piloto sempre foi.
public record FormatoPricetab(String separador, int tamanhoDescricao, boolean descricaoCortada,
                              boolean emendarLinhas, Charset codificacao, boolean internoSemKgEhUnidade,
                              int tamanhoPagina) {

    public static final FormatoPricetab PADRAO = new FormatoPricetab("|", 40, false, true,
            StandardCharsets.ISO_8859_1, true, 500);

    private static final Jsonb JSONB = JsonbBuilder.create();

    @SuppressWarnings("unchecked")
    public static FormatoPricetab de(FormatoOrigemEntity formato) {
        if (formato == null || formato.getParametros() == null) {
            return PADRAO;
        }
        Map<String, Object> p = JSONB.fromJson(formato.getParametros(), Map.class);
        return new FormatoPricetab(
                texto(p.get("separador"), PADRAO.separador()),
                inteiro(p.get("tamanhoDescricao"), PADRAO.tamanhoDescricao()),
                booleano(p.get("descricaoCortada"), PADRAO.descricaoCortada()),
                booleano(p.get("emendarLinhas"), PADRAO.emendarLinhas()),
                p.get("codificacao") == null ? PADRAO.codificacao() : Charset.forName(p.get("codificacao").toString()),
                booleano(p.get("internoSemKgEhUnidade"), PADRAO.internoSemKgEhUnidade()),
                inteiro(p.get("tamanhoPagina"), PADRAO.tamanhoPagina()));
    }

    private static String texto(Object valor, String padrao) {
        return valor == null ? padrao : valor.toString();
    }

    private static int inteiro(Object valor, int padrao) {
        return valor instanceof Number n ? n.intValue() : padrao;
    }

    private static boolean booleano(Object valor, boolean padrao) {
        return valor instanceof Boolean b ? b : padrao;
    }
}
