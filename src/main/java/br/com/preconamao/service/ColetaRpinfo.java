package br.com.preconamao.service;

import io.quarkus.logging.Log;
import jakarta.json.bind.Jsonb;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Dialeto RPInfo ("RP Services") da coleta por API (scripts/031), conforme as respostas REAIS da
// homologação (laboratorio/loja5_api_rpinfo/respostas, 07/10/2026):
//   - POST /v1.2/auth {"usuario","senha"} -> response.content.token (vale 1 h); o token vai no
//     cabeçalho "token" (não "Authorization: Bearer"); sem token/vencido -> 401;
//   - GET /v1.1/departamentos -> response.content[{codigo, descricao}] (o produto só traz o código);
//   - GET /v3.2/produtounidade/listaprodutos/{lastID}/unidade/{CNPJ}/detalhado/ativos?limit=N ->
//     response.produtos[], ordenados por Codigo; a próxima página começa no último Codigo.
// Converte cada página na hora (a resposta tem ~6 KB por produto; não guarda o bruto).
class ColetaRpinfo {

    private static final Duration TEMPO_RESPOSTA = Duration.ofSeconds(60);
    private static final int MAXIMO_PAGINAS = 2000;
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final HttpClient http;
    private final Jsonb jsonb;
    private String token;

    ColetaRpinfo(HttpClient http, Jsonb jsonb) {
        this.http = http;
        this.jsonb = jsonb;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> baixarTudo(ColetaApiService.Acesso acesso, LocalDate hoje) throws IOException, InterruptedException {
        if (acesso.unidade() == null || acesso.unidade().isBlank()) {
            throw new IOException("credencial sem a unidade (CNPJ da loja no sistema RPInfo)");
        }
        long inicio = System.currentTimeMillis();
        token = pedirToken(acesso);

        Map<String, String> departamentos = new HashMap<>();
        Map<String, Object> respostaDeptos = (Map<String, Object>) get(acesso, "/v1.1/departamentos", "departamentos").get("response");
        for (Map<String, Object> d : (List<Map<String, Object>>) respostaDeptos.getOrDefault("content", List.of())) {
            departamentos.put(ColetaApiService.texto(d.get("codigo")), ColetaApiService.texto(d.get("descricao")));
        }

        Map<String, Map<String, Object>> porCodigo = new LinkedHashMap<>();
        Contagem contagem = new Contagem();
        long ultimoCodigo = 0;
        int paginas = 0;
        String unidade = URLEncoder.encode(acesso.unidade().trim(), StandardCharsets.UTF_8);
        while (paginas < MAXIMO_PAGINAS) {
            Map<String, Object> resposta = (Map<String, Object>) get(acesso, "/v3.2/produtounidade/listaprodutos/" + ultimoCodigo
                    + "/unidade/" + unidade + "/detalhado/ativos?limit=" + acesso.tamanhoPagina(), "página " + (paginas + 1)).get("response");
            paginas++;
            List<Map<String, Object>> produtos = (List<Map<String, Object>>) resposta.getOrDefault("produtos", List.of());
            for (Map<String, Object> produto : produtos) {
                converter(produto, departamentos, hoje, porCodigo, contagem);
            }
            // Fim: página incompleta ou vazia (depois da última vem "produtos": [] com status ok —
            // confirmado na homologação em 08/10).
            if (produtos.size() < acesso.tamanhoPagina()) {
                break;
            }
            long proximo = ((Number) produtos.get(produtos.size() - 1).get("Codigo")).longValue();
            if (proximo <= ultimoCodigo) {
                throw new IOException("paginação não avançou (último Codigo " + proximo + ")");
            }
            ultimoCodigo = proximo;
        }
        Log.infof("Coleta RPInfo: %d páginas em %d ms; %d produtos -> %d códigos; fora: %d \"não encontrado\", %d caixas sem preço",
                paginas, System.currentTimeMillis() - inicio, contagem.produtos, porCodigo.size(), contagem.naoEncontrado, contagem.caixas);
        return ColetaApiService.ordenados(porCodigo);
    }

    static final class Contagem {
        int produtos;
        int naoEncontrado;
        int caixas;
    }

    // Um produto da RPInfo -> uma linha por código de barras (principal + codBarrasAlterados).
    //   - preço = PrecoPDV (o que o caixa cobra; o "Preco" já veio diferente dele na real, ex.
    //     Sazón 97,97 x 5,97) — HIPÓTESE a confirmar com o técnico;
    //   - oferta só dentro da validade: Oferta "S" e DataOferta (fim) >= hoje -> preço normal =
    //     PrecoNormal e promoção = PrecoPDV. Vencida (a RPInfo não filtra) -> fica só o PrecoPDV;
    //   - balança pelo campo Balanca: "P" (pesável, visto na real: açougue, código interno com dígito
    //     verificador e TipoEmbalagem "KG"); "S" aceito por garantia. "Funcao" ("KG", "VM;V4;KG"...)
    //     é a unidade do preço por kg da etiqueta, não venda a peso;
    //   - descrição: sem o "*" da frente e sem o próprio Codigo no fim (ex. real "*ave Coração Kg Big",
    //     "Ave File Peito Kg Big 133167"), espaços repetidos viram um;
    //   - descrição "NÃO ENCONTRADO NO RP ÁTOMO" = cadastro incompleto no ERP: não entra;
    //   - código auxiliar com precoVenda > 0 tem preço próprio; com fatorEmbalagem > 1 e sem preço é
    //     a CAIXA (DUN-14) e não entra (o preço da unidade não vale para a caixa).
    @SuppressWarnings("unchecked")
    static void converter(Map<String, Object> p, Map<String, String> departamentos, LocalDate hoje,
                          Map<String, Map<String, Object>> porCodigo, Contagem contagem) {
        if (Boolean.FALSE.equals(p.get("Ativo"))) {
            return;
        }
        contagem.produtos++;
        String descricao = ColetaApiService.texto(p.get("Descricao"));
        if (descricao == null || descricao.isBlank()) {
            return;
        }
        descricao = limparDescricao(descricao, ColetaApiService.texto(p.get("Codigo")));
        if (descricao.isEmpty() || semAcento(descricao).toUpperCase().startsWith("NAO ENCONTRADO")) {
            contagem.naoEncontrado++;
            return;
        }
        Integer precoCaixa = positivo(ColetaApiService.centavos(p.get("PrecoPDV")));
        if (precoCaixa == null) {
            precoCaixa = ColetaApiService.centavos(p.get("Preco"));
        }
        if (precoCaixa == null) {
            return;
        }
        Integer preco = precoCaixa;
        Integer promocao = null;
        LocalDate fim = data(p.get("DataOferta"));
        LocalDate inicioOferta = data(p.get("DtIniOferta"));
        Integer normal = positivo(ColetaApiService.centavos(p.get("PrecoNormal")));
        if ("S".equals(p.get("Oferta")) && fim != null && !hoje.isAfter(fim) && normal != null && normal > precoCaixa) {
            preco = normal;
            promocao = precoCaixa;
        }
        String departamento = ColetaApiService.texto(p.get("Departamento"));
        String secao = departamento != null && !departamento.isBlank() ? departamento
                : departamentos.get(ColetaApiService.texto(p.get("CodigoDepartamento")));
        String codigoPrincipal = ColetaApiService.texto(p.get("CodigoBarras"));
        String unidade = "P".equals(p.get("Balanca")) || "S".equals(p.get("Balanca")) ? "KG"
                : codigoPrincipal != null && codigoPrincipal.trim().matches("0{5}\\d*") ? null : "UN";

        Map<String, Object> principal = ColetaApiService.novoItem(porCodigo, codigoPrincipal, descricao, secao, unidade, preco);
        if (principal != null && promocao != null) {
            oferta(principal, promocao, inicioOferta, fim);
        }
        for (Map<String, Object> alt : (List<Map<String, Object>>) p.getOrDefault("codBarrasAlterados", List.of())) {
            Integer proprio = positivo(ColetaApiService.centavos(alt.get("precoVenda")));
            Number fator = (Number) alt.get("fatorEmbalagem");
            if (proprio == null && fator != null && fator.doubleValue() > 1) {
                contagem.caixas++;
                continue;
            }
            Map<String, Object> item = ColetaApiService.novoItem(porCodigo, alt.get("codigoBarras"), descricao, secao, unidade,
                    proprio != null ? proprio : preco);
            if (item != null && proprio == null && promocao != null) {
                oferta(item, promocao, inicioOferta, fim);
            }
        }
    }

    static String limparDescricao(String descricao, String codigoInterno) {
        String limpa = descricao.trim().replaceAll("\\s+", " ").replaceFirst("^\\*+\\s*", "");
        if (codigoInterno != null && limpa.endsWith(" " + codigoInterno)) {
            limpa = limpa.substring(0, limpa.length() - codigoInterno.length() - 1).trim();
        }
        return limpa.isEmpty() ? limpa : Character.toUpperCase(limpa.charAt(0)) + limpa.substring(1);
    }

    private static void oferta(Map<String, Object> item, Integer promocao, LocalDate inicio, LocalDate fim) {
        item.put("promocao", promocao);
        item.put("promocaoInicio", inicio == null ? null : inicio.toString());
        item.put("promocaoFim", fim.toString());
    }

    private static Integer positivo(Integer centavos) {
        return centavos != null && centavos > 0 ? centavos : null;
    }

    // "15-10-2023" -> data; vazio ou outro formato -> null.
    static LocalDate data(Object valor) {
        String texto = ColetaApiService.texto(valor);
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(texto.trim(), DATA);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String semAcento(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    @SuppressWarnings("unchecked")
    private String pedirToken(ColetaApiService.Acesso acesso) throws IOException, InterruptedException {
        HttpResponse<String> resposta = http.send(HttpRequest.newBuilder(URI.create(acesso.url() + "/v1.2/auth"))
                        .timeout(TEMPO_RESPOSTA)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonb.toJson(Map.of("usuario", acesso.usuario(), "senha", acesso.segredo()))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        // Senha errada na real = HTTP 500 com {"error": "Não foi possível autenticar o usuário"}:
        // a mensagem vai junto para não parecer "sistema da loja fora do ar".
        if (resposta.statusCode() != 200) {
            throw new IOException("autenticação respondeu HTTP " + resposta.statusCode() + motivo(lerJson(resposta.body())));
        }
        Map<String, Object> corpo = (Map<String, Object>) jsonb.fromJson(resposta.body(), Map.class).get("response");
        Map<String, Object> conteudo = corpo == null ? null : (Map<String, Object>) corpo.get("content");
        if (conteudo == null || conteudo.get("token") == null) {
            throw new IOException("autenticação sem token na resposta");
        }
        return conteudo.get("token").toString();
    }

    // GET com o token; 401 (token vencido no meio da coleta): pede outro UMA vez e repete.
    @SuppressWarnings("unchecked")
    private Map<String, Object> get(ColetaApiService.Acesso acesso, String caminho, String oQue) throws IOException, InterruptedException {
        HttpResponse<String> resposta = enviar(acesso, caminho);
        if (resposta.statusCode() == 401) {
            token = pedirToken(acesso);
            resposta = enviar(acesso, caminho);
        }
        Map<String, Object> corpo = lerJson(resposta.body());
        Object envelope = corpo == null ? null : corpo.get("response");
        if (resposta.statusCode() != 200 || !(envelope instanceof Map)
                || !"ok".equals(((Map<String, Object>) envelope).get("status"))) {
            throw new IOException(oQue + " respondeu HTTP " + resposta.statusCode() + motivo(corpo));
        }
        return corpo;
    }

    // Corpo que não é JSON (ex.: página de erro de um proxy) -> null: fica só o código HTTP.
    @SuppressWarnings("unchecked")
    private Map<String, Object> lerJson(String texto) {
        try {
            return jsonb.fromJson(texto, Map.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Os 3 formatos de erro vistos na homologação (08/10):
    //   {"response": {"status": "error", "messages": [...]}}  (consulta com problema)
    //   {"error": "Não foi possível autenticar o usuário", "stackTrace": "..."}  (login, HTTP 500)
    //   {"appVersion": "...", "content": "Não autorizado"}  (sem token ou vencido, HTTP 401)
    // Nunca leva o stackTrace para o log.
    static String motivo(Map<String, Object> corpo) {
        if (corpo == null) {
            return "";
        }
        if (corpo.get("response") != null) {
            return mensagens(corpo.get("response"));
        }
        Object texto = corpo.get("error") != null ? corpo.get("error") : corpo.get("content");
        return texto instanceof String s && !s.isBlank() ? ": " + (s.length() > 300 ? s.substring(0, 300) + "…" : s) : "";
    }

    // Erro da RPInfo: status "error" e mensagens ("Falha ao carregar dados", "Exception: ...",
    // "StackTrace: ..."). Vão para o log as duas primeiras, sem o StackTrace.
    @SuppressWarnings("unchecked")
    static String mensagens(Object envelope) {
        if (!(envelope instanceof Map)) {
            return "";
        }
        List<String> uteis = new ArrayList<>();
        for (Object m : (List<Object>) ((Map<String, Object>) envelope).getOrDefault("messages", List.of())) {
            Object mensagem = m instanceof Map ? ((Map<String, Object>) m).get("message") : null;
            if (mensagem != null && !mensagem.toString().startsWith("StackTrace") && uteis.size() < 2) {
                uteis.add(mensagem.toString());
            }
        }
        String texto = uteis.isEmpty() ? "" : ": " + String.join(" | ", uteis);
        return texto.length() > 300 ? texto.substring(0, 300) + "…" : texto;
    }

    private HttpResponse<String> enviar(ColetaApiService.Acesso acesso, String caminho) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(acesso.url() + caminho))
                        .timeout(TEMPO_RESPOSTA)
                        .header("token", token)
                        .header("Accept", "application/json")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
