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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.LongFunction;

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

    // Data/hora da RPInfo é a do ERP (horário de Brasília).
    static final ZoneId FUSO_ERP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    // Resultado da coleta incremental: as linhas (formato padrão) dos produtos que mudaram e os
    // códigos internos do ERP afetados (mudaram, foram desativados ou excluídos).
    record Parcial(List<Map<String, Object>> itens, List<String> internos) {
    }

    // Coleta COMPLETA: todos os produtos ativos.
    List<Map<String, Object>> baixarTudo(ColetaApiService.Acesso acesso, LocalDate hoje) throws IOException, InterruptedException {
        long inicio = System.currentTimeMillis();
        Map<String, String> departamentos = iniciar(acesso);
        Map<String, Map<String, Object>> porCodigo = new LinkedHashMap<>();
        Contagem contagem = new Contagem();
        String unidade = unidade(acesso);
        int paginas = paginar(acesso, "produtos", "Codigo",
                ultimo -> "/v3.2/produtounidade/listaprodutos/" + ultimo + "/unidade/" + unidade + "/detalhado/ativos?limit=" + acesso.tamanhoPagina(),
                produto -> converter(produto, departamentos, hoje, porCodigo, contagem));
        Log.infof("Coleta RPInfo completa: %d páginas em %d ms; %d produtos -> %d códigos; fora: %d \"não encontrado\", %d caixas sem preço",
                paginas, System.currentTimeMillis() - inicio, contagem.produtos, porCodigo.size(), contagem.naoEncontrado, contagem.caixas);
        return ColetaApiService.ordenados(porCodigo);
    }

    // Coleta INCREMENTAL: produtos com DataHoraManutencao a partir de "desde" (rota SEM /ativos: o
    // desativado também é mudança e vem com Ativo = false) + excluídos desde o dia de "desde"
    // (a rota de excluídos só aceita o dia). Normalmente 4 chamadas: login, departamentos,
    // mudanças e excluídos.
    Parcial baixarDesde(ColetaApiService.Acesso acesso, LocalDate hoje, OffsetDateTime desde) throws IOException, InterruptedException {
        long inicio = System.currentTimeMillis();
        Map<String, String> departamentos = iniciar(acesso);
        Map<String, Map<String, Object>> porCodigo = new LinkedHashMap<>();
        Set<String> internos = new TreeSet<>();
        Contagem contagem = new Contagem();
        String unidade = unidade(acesso);
        LocalDateTime desdeErp = desde.atZoneSameInstant(FUSO_ERP).toLocalDateTime();
        String dataHora = desdeErp.format(DATA_HORA).replace(" ", "%20");
        int paginas = paginar(acesso, "produtos", "Codigo",
                ultimo -> "/v3.2/produtounidade/listaprodutos/" + ultimo + "/unidade/" + unidade + "/detalhado/dataHoraManutencao/"
                        + dataHora + "?limit=" + acesso.tamanhoPagina(),
                produto -> {
                    internos.add(interno(produto.get("Codigo")));
                    converter(produto, departamentos, hoje, porCodigo, contagem);
                });
        int mudados = internos.size();
        paginas += paginar(acesso, "excluidos", "codigoProduto",
                ultimo -> "/v1.1/produto/excluidos/lastid/" + ultimo + "/dataexclusao/" + desdeErp.toLocalDate().format(DATA),
                excluido -> internos.add(interno(excluido.get("codigoProduto"))));
        Log.infof("Coleta RPInfo incremental desde %s: %d páginas em %d ms; %d produtos mudados, %d excluídos -> %d códigos",
                desdeErp, paginas, System.currentTimeMillis() - inicio, mudados, internos.size() - mudados, porCodigo.size());
        return new Parcial(ColetaApiService.ordenados(porCodigo), List.copyOf(internos));
    }

    // Login + nomes dos departamentos (o produto só traz o código).
    @SuppressWarnings("unchecked")
    private Map<String, String> iniciar(ColetaApiService.Acesso acesso) throws IOException, InterruptedException {
        if (acesso.unidade() == null || acesso.unidade().isBlank()) {
            throw new IOException("credencial sem a unidade (CNPJ da loja no sistema RPInfo)");
        }
        token = pedirToken(acesso);
        Map<String, String> departamentos = new HashMap<>();
        Map<String, Object> respostaDeptos = (Map<String, Object>) get(acesso, "/v1.1/departamentos", "departamentos").get("response");
        for (Map<String, Object> d : (List<Map<String, Object>>) respostaDeptos.getOrDefault("content", List.of())) {
            departamentos.put(ColetaApiService.texto(d.get("codigo")), ColetaApiService.texto(d.get("descricao")));
        }
        return departamentos;
    }

    private static String unidade(ColetaApiService.Acesso acesso) {
        return URLEncoder.encode(acesso.unidade().trim(), StandardCharsets.UTF_8);
    }

    static String interno(Object codigo) {
        return codigo instanceof BigDecimal n ? n.toPlainString() : String.valueOf(codigo);
    }

    // Paginação da RPInfo pelo último código: a próxima página começa no código do último item; o
    // fim é a página incompleta ou vazia (depois da última vem a lista vazia com status ok —
    // confirmado na homologação em 08/10). Devolve o número de chamadas.
    @SuppressWarnings("unchecked")
    private int paginar(ColetaApiService.Acesso acesso, String lista, String campoCodigo, LongFunction<String> caminho,
                        Consumer<Map<String, Object>> cadaItem) throws IOException, InterruptedException {
        long ultimoCodigo = 0;
        int paginas = 0;
        while (paginas < MAXIMO_PAGINAS) {
            Map<String, Object> resposta = (Map<String, Object>) get(acesso, caminho.apply(ultimoCodigo),
                    lista + ", página " + (paginas + 1)).get("response");
            paginas++;
            List<Map<String, Object>> itens = (List<Map<String, Object>>) resposta.getOrDefault(lista, List.of());
            itens.forEach(cadaItem);
            if (itens.size() < acesso.tamanhoPagina()) {
                break;
            }
            long proximo = ((Number) itens.get(itens.size() - 1).get(campoCodigo)).longValue();
            if (proximo <= ultimoCodigo) {
                throw new IOException("paginação não avançou (último código " + proximo + ")");
            }
            ultimoCodigo = proximo;
        }
        return paginas;
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

        String codigoInterno = interno(p.get("Codigo"));
        Map<String, Object> principal = ColetaApiService.novoItem(porCodigo, codigoPrincipal, descricao, secao, unidade, preco);
        if (principal != null) {
            principal.put("codigoInterno", codigoInterno);
            if (promocao != null) {
                oferta(principal, promocao, inicioOferta, fim);
            }
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
            if (item != null) {
                item.put("codigoInterno", codigoInterno);
                if (proprio == null && promocao != null) {
                    oferta(item, promocao, inicioOferta, fim);
                }
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
