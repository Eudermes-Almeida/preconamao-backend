package br.com.preconamao.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

// Conector de banco (scripts/037): uma linha da VIEW padrão que a TI da loja cria no ERP
// (vw_simplifica_precos, contrato em laboratorio/conector_banco/CONTRATO_VIEW.md) -> um item de
// carga, no mesmo formato da coleta RPInfo (ColetaApiService.novoItem). Colunas:
//   codigo_barras, descricao, preco          obrigatórias (uma linha por código de barras)
//   preco_promocional, promocao_ate          oferta: só vale com preço menor que o normal e
//                                            validade de hoje em diante (vencida = preço normal)
//   unidade                                  "KG" = vendido na balança; "UN" = unidade; outro = não sabe
//   secao                                    vira a seção do produto (mapa da loja)
//   codigo_interno                           código do produto no ERP (agrupa códigos auxiliares);
//                                            sem ele, cada código de barras é um produto
//   ativo                                    "N", "0" ou falso = fora da loja (não entra)
// O agente manda as linhas como o banco devolveu (nomes das colunas em minúsculas); números podem
// vir como número ou texto com ponto ou vírgula.
final class ColetaViewPadrao {

    private ColetaViewPadrao() {
    }

    static void converter(Map<String, Object> linha, LocalDate hoje, Map<String, Map<String, Object>> porCodigo,
                          ColetaRpinfo.Contagem contagem) {
        if (inativo(linha.get("ativo"))) {
            return;
        }
        contagem.produtos++;
        String descricao = ColetaApiService.texto(linha.get("descricao"));
        if (descricao == null || descricao.isBlank()) {
            return;
        }
        descricao = descricao.trim().replaceAll("\\s+", " ");
        Integer preco = ColetaApiService.centavos(linha.get("preco"));
        if (preco == null || preco < 0) {
            return;
        }
        String unidade = ColetaApiService.texto(linha.get("unidade"));
        unidade = unidade == null ? null : switch (unidade.trim().toUpperCase()) {
            case "KG" -> "KG";
            case "UN" -> "UN";
            default -> null;
        };
        Map<String, Object> item = ColetaApiService.novoItem(porCodigo, linha.get("codigo_barras"), descricao,
                ColetaApiService.texto(linha.get("secao")), unidade, preco);
        if (item == null) {
            return;
        }
        item.put("codigoInterno", interno(linha));
        Integer promocao = ColetaApiService.centavos(linha.get("preco_promocional"));
        LocalDate fim = data(linha.get("promocao_ate"));
        if (promocao != null && promocao > 0 && promocao < preco && fim != null && !hoje.isAfter(fim)) {
            ColetaRpinfo.oferta(item, promocao, null, fim);
        }
    }

    // "2026-10-16", "2026-10-16 00:00:00", "2026-10-16T00:00" ou "16/10/2026" -> data; outro -> null.
    static LocalDate data(Object valor) {
        String t = ColetaApiService.texto(valor);
        if (t == null || t.isBlank()) {
            return null;
        }
        t = t.trim();
        try {
            if (t.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                return LocalDate.parse(t.substring(0, 10));
            }
            if (t.matches("\\d{2}/\\d{2}/\\d{4}.*")) {
                return LocalDate.parse(t.substring(6, 10) + "-" + t.substring(3, 5) + "-" + t.substring(0, 2));
            }
        } catch (java.time.DateTimeException e) {
            return null;
        }
        return null;
    }

    static String interno(Object valor) {
        String texto = ColetaApiService.texto(valor);
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    // Produto do ERP a que a linha pertence: codigo_interno; sem ele (VIEW sem a coluna, ou vazio), o
    // próprio código de barras, para que um PARCIAL com ativo = N ou um código que sumiu da VIEW
    // ainda tire o produto da loja (o agente manda o mesmo valor em "excluidos").
    static String interno(Map<?, ?> linha) {
        String interno = interno(linha.get("codigo_interno"));
        return interno != null ? interno : interno(linha.get("codigo_barras"));
    }

    private static boolean inativo(Object ativo) {
        if (ativo == null) {
            return false;
        }
        if (ativo instanceof Boolean b) {
            return !b;
        }
        if (ativo instanceof Number n) {
            return new BigDecimal(n.toString()).signum() == 0;
        }
        String t = ativo.toString().trim().toUpperCase();
        return t.equals("N") || t.equals("0") || t.equals("NAO") || t.equals("NÃO") || t.equals("FALSE") || t.equals("F");
    }
}
