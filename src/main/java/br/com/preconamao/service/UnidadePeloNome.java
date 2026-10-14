package br.com.preconamao.service;

import java.util.Locale;
import java.util.regex.Pattern;

// Produto interno de balança cuja loja não informa a unidade (PRICETAB de 16 posições): às vezes o
// próprio nome, mesmo cortado, já diz como é vendido — e o app não precisa perguntar ao cliente.
//   "HF BANANA MACA K"  -> "KG" cortado no 16º caractere: por quilo
//   "HF ABACAXI UN", "HF ALFACE LISA U" (UN cortado) -> por unidade
//   "HF CHUCHU BDJA", "HF UVA ROSADA BD" (bandeja, preço fechado) -> por unidade
// Sem nenhuma dessas marcas: continua indefinido (o app pergunta).
public final class UnidadePeloNome {

    public enum Unidade { KG, UN }

    private static final Pattern KG_CORTADO = Pattern.compile("\\sK$");
    private static final Pattern UNIDADE = Pattern.compile("(^|\\s)(UN|BD|BDJ|BDJA)(\\s|$)|\\sU$");

    private UnidadePeloNome() {
    }

    // null = o nome não diz.
    public static Unidade de(String descricaoOriginal) {
        if (descricaoOriginal == null) {
            return null;
        }
        String nome = descricaoOriginal.strip().toUpperCase(Locale.ROOT);
        if (KG_CORTADO.matcher(nome).find()) {
            return Unidade.KG;
        }
        if (UNIDADE.matcher(nome).find()) {
            return Unidade.UN;
        }
        return null;
    }
}
