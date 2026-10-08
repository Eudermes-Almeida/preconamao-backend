package br.com.preconamao.service;

import br.com.preconamao.entity.CargaPricetabEntity;
import br.com.preconamao.entity.CredencialApiEntity;
import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.entity.ProdutoEntity;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.persistence.EntityManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Coleta de preços nas lojas cuja origem é a API do sistema de gestão (regra 12 do multi-loja):
// o NOSSO servidor consulta a loja (nada instalado nela). A cada minuto vê quais lojas estão na
// hora (intervalo da loja) e coleta uma por vez: pede o token, busca todas as páginas, converte
// para o formato padrão e calcula o hash. Mudou -> vira carga na mesma fila do PRICETAB; não
// mudou -> só registra o "sinal" (a loja respondeu), que alimenta a proteção de preço (11g).
// Falhou -> só no próximo ciclo, sem repetir em seguida (não sobrecarregar o sistema da loja).
@ApplicationScoped
public class ColetaApiService {

    private static final Duration TEMPO_CONEXAO = Duration.ofSeconds(10);
    private static final Duration TEMPO_RESPOSTA = Duration.ofSeconds(30);
    private static final int INTERVALO_PADRAO_MIN = 5;
    private static final int MAXIMO_PAGINAS = 1000;

    @Inject
    EntityManager entityManager;

    @Inject
    LojaService lojaService;

    @Inject
    CargaPricetabService cargaService;

    @Inject
    CifraCredenciais cifra;

    // HTTP/1.1: em http (sem TLS) o cliente tentaria negociar HTTP/2 ("h2c"), e servidores simples recusam.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TEMPO_CONEXAO).build();
    private final Jsonb jsonb = JsonbBuilder.create();

    record Acesso(String url, String usuario, String segredo, int tamanhoPagina, String dialeto, String unidade) {
    }

    @Scheduled(every = "1m", delayed = "40s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void coletarLojasNaHora() {
        OffsetDateTime agora = OffsetDateTime.now();
        // Lojas cuja API é consultada pelo AGENTE na loja (scripts/034) não são coletadas aqui.
        List<LojaEntity> lojas = QuarkusTransaction.requiringNew().call(() -> entityManager.createQuery(
                        "SELECT l FROM LojaEntity l WHERE l.tipoOrigem = :api AND l.ativa = true ORDER BY l.id", LojaEntity.class)
                .setParameter("api", LojaEntity.ORIGEM_API)
                .getResultList().stream()
                .filter(l -> !FormatoPricetab.COLETA_AGENTE.equals(FormatoPricetab.de(lojaService.formato(l)).coleta()))
                .toList());
        for (LojaEntity loja : lojas) {
            int intervalo = loja.getIntervaloColetaMin() == null ? INTERVALO_PADRAO_MIN : loja.getIntervaloColetaMin();
            if (loja.getUltimaColetaEm() == null || !loja.getUltimaColetaEm().isAfter(agora.minusMinutes(intervalo).plusSeconds(5))) {
                coletar(loja.getId(), false);
            }
        }
    }

    // Coleta incremental (scripts/033, proposta de 08/10/2026): entre duas coletas COMPLETAS, só o
    // que mudou. A completa (conferência) é 1 vez por dia a partir das 3h (horário de Brasília), na
    // 1ª coleta da loja, no "coletar agora" e sempre no dialeto SIMPLES (que não tem incremental).
    static final LocalTime HORA_COMPLETA = LocalTime.of(3, 0);
    // Folga no "desde quando": o ERP pode gravar algo durante a nossa coleta, e os relógios diferem.
    static final Duration FOLGA_INCREMENTAL = Duration.ofMinutes(10);

    static boolean precisaCompleta(OffsetDateTime ultimaCompleta, OffsetDateTime incrementalDesde, OffsetDateTime agora) {
        if (ultimaCompleta == null || incrementalDesde == null) {
            return true;
        }
        ZonedDateTime agoraErp = agora.atZoneSameInstant(ColetaRpinfo.FUSO_ERP);
        ZonedDateTime marco = agoraErp.toLocalDate().atTime(HORA_COMPLETA).atZone(ColetaRpinfo.FUSO_ERP);
        if (agoraErp.isBefore(marco)) {
            marco = marco.minusDays(1);
        }
        return ultimaCompleta.isBefore(marco.toOffsetDateTime());
    }

    // Devolve o que aconteceu (para o log e para o botão "coletar agora" do laboratório).
    // completaForcada: o "coletar agora" faz a completa; o laboratório pode pedir a incremental.
    public String coletar(Integer lojaId, boolean completaForcada) {
        OffsetDateTime inicio = OffsetDateTime.now();
        Acesso acesso;
        OffsetDateTime desde;
        try {
            Object[] preparo = QuarkusTransaction.requiringNew().call(() -> {
                LojaEntity loja = entityManager.find(LojaEntity.class, lojaId);
                if (FormatoPricetab.COLETA_AGENTE.equals(FormatoPricetab.de(lojaService.formato(loja)).coleta())) {
                    throw new IllegalStateException("loja alimentada pelo agente na loja (o servidor só recebe os pacotes)");
                }
                loja.setUltimaColetaEm(inicio);
                CredencialApiEntity credencial = entityManager.find(CredencialApiEntity.class, lojaId);
                if (credencial == null) {
                    throw new IllegalStateException("loja sem credencial de API cadastrada");
                }
                FormatoPricetab formato = FormatoPricetab.de(lojaService.formato(loja));
                boolean incremental = !completaForcada && FormatoPricetab.DIALETO_RPINFO.equals(formato.dialeto())
                        && !precisaCompleta(loja.getUltimaColetaCompletaEm(), loja.getIncrementalDesde(), inicio);
                return new Object[]{new Acesso(credencial.getUrl().replaceAll("/+$", ""), credencial.getUsuario(),
                        cifra.decifrar(credencial.getSegredoCifrado()), formato.tamanhoPagina(), formato.dialeto(),
                        credencial.getUnidade()), incremental ? loja.getIncrementalDesde().minus(FOLGA_INCREMENTAL) : null};
            });
            acesso = (Acesso) preparo[0];
            desde = (OffsetDateTime) preparo[1];
        } catch (Exception e) {
            Log.warnf("Coleta da API da loja %d não começou: %s", lojaId, e.getMessage());
            return "Não começou: " + e.getMessage();
        }
        return desde == null ? coletarCompleta(lojaId, acesso, inicio) : coletarIncremental(lojaId, acesso, inicio, desde);
    }

    private String coletarCompleta(Integer lojaId, Acesso acesso, OffsetDateTime inicio) {
        List<Map<String, Object>> itens;
        try {
            // Cada sistema de gestão fala o seu "dialeto" (scripts/031); todos viram o formato padrão.
            itens = FormatoPricetab.DIALETO_RPINFO.equals(acesso.dialeto())
                    ? new ColetaRpinfo(http, jsonb).baixarTudo(acesso, LocalDate.now(ColetaRpinfo.FUSO_ERP))
                    : converter(baixarTudo(acesso));
        } catch (Exception e) {
            // A mensagem nunca leva o segredo (só status/endereço).
            Log.warnf("Coleta da API da loja %d falhou: %s (tenta de novo no próximo ciclo)", lojaId, e.getMessage());
            return "Falhou: " + e.getMessage();
        }

        byte[] conteudo = jsonb.toJson(itens).getBytes(StandardCharsets.UTF_8);
        String hash = LojaService.sha256(conteudo);
        return QuarkusTransaction.requiringNew().call(() -> {
            LojaEntity loja = entityManager.find(LojaEntity.class, lojaId);
            loja.setUltimoSinalEm(OffsetDateTime.now());
            loja.setUltimaColetaCompletaEm(inicio);
            loja.setIncrementalDesde(inicio);
            lojaService.registrarHashInformado(loja, hash);
            if (hash.equals(loja.getHashAplicado())) {
                return "Completa: sem mudança (" + itens.size() + " produtos): só o sinal.";
            }
            CargaPricetabEntity ultima = cargaService.ultimaCarga(lojaId);
            if (ultima != null && hash.equals(ultima.getHash())) {
                return "Completa: mesmos dados da carga nº " + ultima.getId() + " (" + ultima.getSituacao() + ").";
            }
            CargaPricetabEntity carga = cargaService.registrar(loja, CargaPricetabEntity.RECEBIDA, LojaEntity.ORIGEM_API,
                    null, conteudo, hash, ".json");
            Log.infof("Coleta completa da API da loja %d: dados mudaram, carga nº %d na fila (%d produtos)", lojaId, carga.getId(), itens.size());
            return "Completa: dados mudaram, carga nº " + carga.getId() + " na fila (" + itens.size() + " produtos).";
        });
    }

    // Só o que mudou desde a última coleta (com a folga). Nada mudou -> só o sinal; mudou -> carga
    // PARCIAL na mesma fila (a trava dos 20% vale para ela também).
    private String coletarIncremental(Integer lojaId, Acesso acesso, OffsetDateTime inicio, OffsetDateTime desde) {
        ColetaRpinfo.Parcial parcial;
        try {
            parcial = new ColetaRpinfo(http, jsonb).baixarDesde(acesso, LocalDate.now(ColetaRpinfo.FUSO_ERP), desde);
        } catch (Exception e) {
            Log.warnf("Coleta incremental da API da loja %d falhou: %s (tenta de novo no próximo ciclo)", lojaId, e.getMessage());
            return "Falhou: " + e.getMessage();
        }
        boolean vazia = parcial.internos().isEmpty();
        byte[] conteudo = jsonb.toJson(Map.of("itens", parcial.itens(), "internos", parcial.internos())).getBytes(StandardCharsets.UTF_8);
        String hash = LojaService.sha256(conteudo);
        return QuarkusTransaction.requiringNew().call(() -> {
            LojaEntity loja = entityManager.find(LojaEntity.class, lojaId);
            loja.setUltimoSinalEm(OffsetDateTime.now());
            loja.setIncrementalDesde(inicio);
            // A mesma mudança volta enquanto estiver dentro da folga: aplicar de novo não muda nada.
            CargaPricetabEntity ultima = cargaService.ultimaCarga(lojaId);
            if (vazia || hash.equals(loja.getHashAplicado()) || ultima != null && hash.equals(ultima.getHash())) {
                return "Incremental: sem mudança desde " + desde.atZoneSameInstant(ColetaRpinfo.FUSO_ERP).toLocalDateTime()
                        + " (" + parcial.internos().size() + " produto(s) já aplicados): só o sinal.";
            }
            lojaService.registrarHashInformado(loja, hash);
            CargaPricetabEntity carga = cargaService.registrar(loja, CargaPricetabEntity.RECEBIDA, LojaEntity.ORIGEM_API,
                    null, conteudo, hash, ".json");
            carga.setTipo(CargaPricetabEntity.PARCIAL);
            Log.infof("Coleta incremental da API da loja %d: %d produto(s) do ERP mudaram, carga nº %d na fila",
                    lojaId, parcial.internos().size(), carga.getId());
            return "Incremental: " + parcial.internos().size() + " produto(s) do ERP mudaram, carga nº " + carga.getId()
                    + " na fila (" + parcial.itens().size() + " códigos).";
        });
    }

    // Token + todas as páginas. Token vencido no meio (401): pede outro UMA vez e continua.
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> baixarTudo(Acesso acesso) throws IOException, InterruptedException {
        String token = pedirToken(acesso);
        List<Map<String, Object>> todos = new ArrayList<>();
        int pagina = 1;
        int totalPaginas = 1;
        while (pagina <= totalPaginas && pagina <= MAXIMO_PAGINAS) {
            HttpResponse<String> resposta = buscarPagina(acesso, token, pagina);
            if (resposta.statusCode() == 401) {
                token = pedirToken(acesso);
                resposta = buscarPagina(acesso, token, pagina);
            }
            if (resposta.statusCode() != 200) {
                throw new IOException("página " + pagina + " respondeu HTTP " + resposta.statusCode());
            }
            Map<String, Object> corpo = jsonb.fromJson(resposta.body(), Map.class);
            totalPaginas = ((Number) corpo.getOrDefault("totalPaginas", 1)).intValue();
            todos.addAll((List<Map<String, Object>>) corpo.getOrDefault("itens", List.of()));
            pagina++;
        }
        return todos;
    }

    @SuppressWarnings("unchecked")
    private String pedirToken(Acesso acesso) throws IOException, InterruptedException {
        HttpResponse<String> resposta = http.send(HttpRequest.newBuilder(URI.create(acesso.url() + "/auth/token"))
                        .timeout(TEMPO_RESPOSTA)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonb.toJson(Map.of("usuario", acesso.usuario(), "senha", acesso.segredo()))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200) {
            throw new IOException("autenticação respondeu HTTP " + resposta.statusCode());
        }
        return (String) jsonb.fromJson(resposta.body(), Map.class).get("access_token");
    }

    private HttpResponse<String> buscarPagina(Acesso acesso, String token, int pagina) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(acesso.url() + "/v1/produtos?pagina=" + pagina
                                + "&tamanho=" + acesso.tamanhoPagina()))
                        .timeout(TEMPO_RESPOSTA)
                        .header("Authorization", "Bearer " + token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // Formato da API -> formato padrão da carga (o mesmo do PRICETAB, com os campos a mais). Cada
    // código de barras do produto vira uma linha (principal e auxiliares); código como número
    // perde os zeros e é padronizado (regra 17); preço vem como número ou texto com ponto.
    // Produto inativo não entra (some do app como se não tivesse vindo).
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> converter(List<Map<String, Object>> brutos) {
        Map<String, Map<String, Object>> porCodigo = new LinkedHashMap<>();
        for (Map<String, Object> bruto : brutos) {
            if (Boolean.FALSE.equals(bruto.get("ativo"))) {
                continue;
            }
            Integer preco = centavos(bruto.get("precoVenda"));
            String descricao = texto(bruto.get("descricao"));
            if (preco == null || descricao == null || descricao.isBlank()) {
                continue;
            }
            Map<String, Object> promocao = (Map<String, Object>) bruto.get("promocao");
            Map<String, Object> atacado = (Map<String, Object>) bruto.get("precoAtacado");
            for (Object codigoBruto : (List<Object>) bruto.getOrDefault("codigosBarras", List.of())) {
                Map<String, Object> item = novoItem(porCodigo, codigoBruto, descricao, texto(bruto.get("secao")),
                        texto(bruto.get("unidade")), preco);
                if (item == null) {
                    continue;
                }
                item.put("promocao", promocao == null ? null : centavos(promocao.get("preco")));
                item.put("promocaoInicio", promocao == null ? null : texto(promocao.get("inicio")));
                item.put("promocaoFim", promocao == null ? null : texto(promocao.get("fim")));
                item.put("atacado", atacado == null ? null : centavos(atacado.get("preco")));
                item.put("atacadoQuantidade", atacado == null || atacado.get("quantidadeMinima") == null ? null
                        : ((Number) atacado.get("quantidadeMinima")).intValue());
                item.put("condicao", texto(bruto.get("condicao")));
            }
        }
        return ordenados(porCodigo);
    }

    // Uma linha do formato padrão (um código de barras), já guardada em porCodigo; promoção, atacado
    // e condição ficam vazios (quem chama preenche). Código inválido -> null (a linha não entra).
    static Map<String, Object> novoItem(Map<String, Map<String, Object>> porCodigo, Object codigoBruto, String descricao,
                                        String secao, String unidade, Integer preco) {
        String codigoOrigem = codigoBruto instanceof BigDecimal n ? n.toPlainString() : String.valueOf(codigoBruto).trim();
        if (!codigoOrigem.matches("\\d{1,14}")) {
            return null;
        }
        String codigo = CodigoBarras.canonico(codigoOrigem);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("codigo", codigo);
        item.put("codigoOrigem", codigoOrigem);
        item.put("descricao", descricao.length() > 120 ? descricao.substring(0, 120) : descricao);
        item.put("descricaoCompleta", descricao);
        item.put("secao", secao);
        item.put("unidade", unidade);
        item.put("preco", preco);
        Map<String, Object> repetido = porCodigo.get(codigo);
        // Mesmo código em dois produtos com preços diferentes: sem preço (regra 19).
        item.put("semPreco", repetido != null && !preco.equals(repetido.get("preco")) ? ProdutoEntity.SEM_PRECO_CONFLITO
                : preco == 0 ? ProdutoEntity.SEM_PRECO_ZERO : null);
        item.put("promocao", null);
        item.put("promocaoInicio", null);
        item.put("promocaoFim", null);
        item.put("atacado", null);
        item.put("atacadoQuantidade", null);
        item.put("condicao", null);
        porCodigo.put(codigo, item);
        return item;
    }

    // Ordem fixa: o mesmo conteúdo dá sempre o mesmo hash.
    static List<Map<String, Object>> ordenados(Map<String, Map<String, Object>> porCodigo) {
        List<Map<String, Object>> itens = new ArrayList<>(porCodigo.values());
        itens.sort(Comparator.comparing(i -> (String) i.get("codigo")));
        return itens;
    }

    static String texto(Object valor) {
        return valor == null ? null : valor.toString();
    }

    // 9.59 (número) ou "9.59" (texto com ponto) -> 959.
    static Integer centavos(Object valor) {
        if (valor == null) {
            return null;
        }
        try {
            return new BigDecimal(valor instanceof BigDecimal n ? n.toPlainString() : valor.toString().trim().replace(",", "."))
                    .movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }
}
