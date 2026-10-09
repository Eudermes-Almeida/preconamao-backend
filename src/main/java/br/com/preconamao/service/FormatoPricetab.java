package br.com.preconamao.service;

import br.com.preconamao.entity.FormatoOrigemEntity;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

// Parâmetros da ficha do formato (formato_origem.parametros, scripts/027). Ausente = PRICETAB de
// 40 posições, como a loja piloto sempre foi. API: tamanhoPagina, dialeto (scripts/031: SIMPLES =
// API simulada v1; RPINFO = API da RPInfo) e coleta (scripts/034: SERVIDOR = o nosso servidor
// consulta a API; AGENTE = o agente na loja consulta e envia os pacotes, o servidor só recebe).
public record FormatoPricetab(String separador, int tamanhoDescricao, boolean descricaoCortada,
                              boolean emendarLinhas, Charset codificacao, boolean internoSemKgEhUnidade,
                              int tamanhoPagina, String dialeto, String coleta) {

    public static final String DIALETO_SIMPLES = "SIMPLES";
    public static final String DIALETO_RPINFO = "RPINFO";
    // Conector de banco (scripts/037): o agente lê a VIEW padrão (vw_simplifica_precos) no banco do
    // ERP e envia as linhas como vieram; a conversão é ColetaViewPadrao.
    public static final String DIALETO_VIEW = "VIEW_PADRAO";
    public static final String COLETA_SERVIDOR = "SERVIDOR";
    public static final String COLETA_AGENTE = "AGENTE";

    public static final FormatoPricetab PADRAO = new FormatoPricetab("|", 40, false, true,
            StandardCharsets.ISO_8859_1, true, 500, DIALETO_SIMPLES, COLETA_SERVIDOR);

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
                inteiro(p.get("tamanhoPagina"), PADRAO.tamanhoPagina()),
                texto(p.get("dialeto"), PADRAO.dialeto()),
                texto(p.get("coleta"), PADRAO.coleta()));
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
