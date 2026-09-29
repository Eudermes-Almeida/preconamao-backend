package br.com.preconamao.service;

import br.com.preconamao.dto.CargaRecebidaDTO;
import br.com.preconamao.dto.SinalRespostaDTO;
import br.com.preconamao.dto.SituacaoLojaDTO;
import br.com.preconamao.entity.CargaPricetabEntity;
import br.com.preconamao.entity.LojaEntity;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

// Carga automática do PRICETAB enviada pelo agente da loja. Recebe e guarda na hora (o agente não
// espera o processamento); um agendador aplica as cargas pendentes, uma de cada vez, cada uma numa
// transação: ou entra inteira, ou nada muda (ver aplicar_carga_pricetab() no script 016).
@ApplicationScoped
public class CargaPricetabService {

    // 15 mil linhas dão ~1 MB; 10 MB sobra e barra envio absurdo.
    public static final int TAMANHO_MAXIMO_BYTES = 10 * 1024 * 1024;

    // Formato Gertec: CODIGO|DESCRICAO(40)|PRECO(10, centavos)||
    private static final Pattern CODIGO = Pattern.compile("\\d{1,14}");
    private static final Pattern PRECO = Pattern.compile("\\d{1,10}");
    private static final int MAX_DESCRICAO = 40;
    private static final int MAX_ERROS_NA_MENSAGEM = 20;
    private static final int CARGAS_COM_ARQUIVO_GUARDADO = 30;

    // Fração dos produtos ativos que, se sumir do arquivo, retém a carga (arquivo cortado/vazio).
    @ConfigProperty(name = "carga.limite-inativacao", defaultValue = "0.20")
    BigDecimal limiteInativacao;

    @Inject
    EntityManager entityManager;

    @Inject
    LojaService lojaService;

    private final Jsonb jsonb = JsonbBuilder.create();

    record Item(String codigo, String descricao, int preco) {
    }

    record Interpretacao(List<Item> itens, int linhasTotal, List<String> erros) {
    }

    // ------------------------------------------------------------------------------------------
    // Recebimento
    // ------------------------------------------------------------------------------------------

    @Transactional
    public CargaRecebidaDTO receber(LojaEntity loja, byte[] arquivo) {
        String hash = LojaService.sha256(arquivo);
        LojaEntity lojaAtual = entityManager.find(LojaEntity.class, loja.getId());
        lojaAtual.setUltimoSinalEm(OffsetDateTime.now());
        lojaAtual.setHashInformado(hash);

        if (hash.equals(lojaAtual.getHashAplicado())) {
            return CargaRecebidaDTO.builder().situacao("JA_APLICADO")
                    .mensagem("Este arquivo já está aplicado.").build();
        }
        // Mesmo arquivo da última carga (ainda na fila, retida ou com erro): não duplica. Só compara
        // com a última — um arquivo que volta a uma versão antiga (preço que foi e voltou) é carga nova.
        CargaPricetabEntity ultima = ultimaCarga(loja.getId());
        if (ultima != null && hash.equals(ultima.getHash())) {
            return paraDTO(ultima, "Este arquivo já foi recebido (carga nº " + ultima.getId() + ").");
        }

        CargaPricetabEntity carga = CargaPricetabEntity.builder()
                .lojaId(loja.getId())
                .recebidaEm(OffsetDateTime.now())
                .hash(hash)
                .tamanhoBytes(arquivo.length)
                .arquivo(arquivo)
                .situacao(CargaPricetabEntity.RECEBIDA)
                .build();
        entityManager.persist(carga);
        CargaRecebidaDTO dto = paraDTO(carga, "Recebido; processamento em alguns segundos.");
        dto.setNovaCarga(true);
        return dto;
    }

    // Sinal de vida do agente (a cada poucos minutos). enviarArquivo = o servidor não tem o arquivo
    // que o agente tem (envio perdido): o agente manda de novo.
    @Transactional
    public SinalRespostaDTO registrarSinal(LojaEntity loja, String hashArquivo) {
        LojaEntity lojaAtual = entityManager.find(LojaEntity.class, loja.getId());
        lojaAtual.setUltimoSinalEm(OffsetDateTime.now());
        lojaAtual.setHashInformado(hashArquivo);

        CargaPricetabEntity ultima = ultimaCarga(loja.getId());
        boolean enviarArquivo = hashArquivo != null
                && !hashArquivo.equals(lojaAtual.getHashAplicado())
                && (ultima == null || !hashArquivo.equals(ultima.getHash()));
        return SinalRespostaDTO.builder()
                .enviarArquivo(enviarArquivo)
                .hashAplicado(lojaAtual.getHashAplicado())
                .ultimaCarga(ultima == null ? null : paraDTO(ultima, ultima.getMensagem()))
                .build();
    }

    @Transactional
    public List<CargaRecebidaDTO> ultimasCargas(Integer lojaId, int quantidade) {
        return entityManager.createQuery(
                        "SELECT c FROM CargaPricetabEntity c WHERE c.lojaId = :loja ORDER BY c.id DESC",
                        CargaPricetabEntity.class)
                .setParameter("loja", lojaId)
                .setMaxResults(quantidade)
                .getResultList().stream()
                .map(c -> paraDTO(c, c.getMensagem()))
                .toList();
    }

    @Transactional
    public SituacaoLojaDTO situacaoLoja(Integer lojaId) {
        LojaEntity loja = entityManager.find(LojaEntity.class, lojaId);
        boolean aplicado = loja.getHashAplicado() != null && loja.getHashAplicado().equals(loja.getHashInformado());
        boolean confiavel = loja.getLimiteSemSinalMin() == null || (lojaService.sinalRecente(loja) && aplicado);
        return SituacaoLojaDTO.builder()
                .loja(loja.getNome())
                .precoConfiavel(confiavel)
                .limiteSemSinalMin(loja.getLimiteSemSinalMin())
                .ultimoSinalEm(loja.getUltimoSinalEm() == null ? null : loja.getUltimoSinalEm().toString())
                .arquivoAplicado(aplicado)
                .alertaAtivo(loja.getAlertaAtivo())
                .ultimasCargas(ultimasCargas(lojaId, 10))
                .build();
    }

    @Transactional
    public CargaRecebidaDTO buscarCarga(Integer lojaId, Long id) {
        CargaPricetabEntity carga = entityManager.find(CargaPricetabEntity.class, id);
        return carga == null || !Objects.equals(carga.getLojaId(), lojaId) ? null : paraDTO(carga, carga.getMensagem());
    }

    // ------------------------------------------------------------------------------------------
    // Processamento
    // ------------------------------------------------------------------------------------------

    // SKIP: nunca duas execuções ao mesmo tempo nesta instância; o "FOR UPDATE SKIP LOCKED" em
    // processarProxima() cobre as duas instâncias que o Render mantém durante um deploy.
    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void processarPendentes() {
        while (true) {
            Long processada;
            try {
                processada = QuarkusTransaction.requiringNew().call(this::processarProxima);
            } catch (FalhaCarga falha) {
                Log.errorf(falha.getCause(), "Carga %d do PRICETAB falhou", falha.cargaId);
                QuarkusTransaction.requiringNew().run(() -> marcarErro(falha.cargaId, falha.getCause()));
                continue;
            } catch (Exception e) {
                Log.error("Falha inesperada ao processar carga do PRICETAB", e);
                return;
            }
            if (processada == null) {
                return;
            }
        }
    }

    static class FalhaCarga extends RuntimeException {
        final Long cargaId;

        FalhaCarga(Long cargaId, Throwable causa) {
            super(causa);
            this.cargaId = cargaId;
        }
    }

    // Pega a carga pendente mais nova (as pendentes mais antigas da mesma loja ficam IGNORADAS: o
    // arquivo novo já traz o estado atual) e aplica, tudo na mesma transação: se o banco rejeitar
    // qualquer coisa, nada da carga fica gravado. Devolve o id processado ou null.
    Long processarProxima() {
        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery(
                        "SELECT id FROM carga_pricetab WHERE situacao = 'RECEBIDA' "
                                + "ORDER BY id DESC LIMIT 1 FOR UPDATE SKIP LOCKED")
                .getResultList();
        if (ids.isEmpty()) {
            return null;
        }
        CargaPricetabEntity carga = entityManager.find(CargaPricetabEntity.class, ids.get(0).longValue());
        try {
            entityManager.createQuery(
                            "UPDATE CargaPricetabEntity c SET c.situacao = :ignorada, c.processadaEm = :agora, "
                                    + "c.mensagem = :mensagem WHERE c.lojaId = :loja AND c.situacao = :recebida AND c.id < :id")
                    .setParameter("ignorada", CargaPricetabEntity.IGNORADA)
                    .setParameter("agora", OffsetDateTime.now())
                    .setParameter("mensagem", "Substituída pela carga nº " + carga.getId() + ", mais nova.")
                    .setParameter("loja", carga.getLojaId())
                    .setParameter("recebida", CargaPricetabEntity.RECEBIDA)
                    .setParameter("id", carga.getId())
                    .executeUpdate();
            aplicar(carga);
            entityManager.flush();
        } catch (RuntimeException e) {
            throw new FalhaCarga(carga.getId(), e);
        }
        return carga.getId();
    }

    void marcarErro(Long cargaId, Throwable causa) {
        CargaPricetabEntity carga = entityManager.find(CargaPricetabEntity.class, cargaId);
        if (carga != null && CargaPricetabEntity.RECEBIDA.equals(carga.getSituacao())) {
            carga.setSituacao(CargaPricetabEntity.ERRO);
            carga.setProcessadaEm(OffsetDateTime.now());
            carga.setMensagem("Erro ao aplicar (nada foi alterado): " + causa.getMessage());
        }
    }

    void aplicar(CargaPricetabEntity carga) {
        Interpretacao interpretacao = interpretar(carga.getArquivo());
        carga.setLinhasTotal(interpretacao.linhasTotal());
        carga.setLinhasInvalidas(interpretacao.erros().size());
        carga.setProcessadaEm(OffsetDateTime.now());

        String errosTexto = interpretacao.erros().isEmpty() ? "" : "\nLinhas ignoradas: "
                + String.join("; ", interpretacao.erros().subList(0, Math.min(MAX_ERROS_NA_MENSAGEM, interpretacao.erros().size())))
                + (interpretacao.erros().size() > MAX_ERROS_NA_MENSAGEM ? " ..." : "");

        List<Map<String, Object>> itens = interpretacao.itens().stream().map(item -> {
            Map<String, Object> mapa = new LinkedHashMap<>();
            mapa.put("codigo", item.codigo());
            mapa.put("descricao", item.descricao());
            mapa.put("preco", item.preco());
            return mapa;
        }).toList();

        String resultadoJson = (String) entityManager.createNativeQuery(
                        "SELECT CAST(aplicar_carga_pricetab(CAST(:itens AS jsonb), :limite) AS text)")
                .setParameter("itens", jsonb.toJson(itens))
                .setParameter("limite", limiteInativacao)
                .getSingleResult();
        @SuppressWarnings("unchecked")
        Map<String, Object> resultado = jsonb.fromJson(resultadoJson, Map.class);

        if (Boolean.TRUE.equals(resultado.get("retida"))) {
            carga.setSituacao(CargaPricetabEntity.RETIDA);
            carga.setMensagem(String.format(
                    "RETIDA por segurança: o arquivo tem %s produto(s) válido(s) e %s dos %s produtos ativos "
                            + "sumiriam (limite: %s%%). Arquivo cortado ou vazio? Nada foi alterado.",
                    resultado.get("itens"), resultado.get("sumiriam"), resultado.get("ativos"),
                    limiteInativacao.movePointRight(2).stripTrailingZeros().toPlainString()) + errosTexto);
            return;
        }

        carga.setSituacao(CargaPricetabEntity.CONCLUIDA);
        carga.setNovos(inteiro(resultado.get("novos")));
        carga.setPrecosAlterados(inteiro(resultado.get("precosAlterados")));
        carga.setDescricoesAlteradas(inteiro(resultado.get("descricoesAlteradas")));
        carga.setInativados(inteiro(resultado.get("inativados")));
        carga.setReativados(inteiro(resultado.get("reativados")));
        carga.setMensagem(String.format("%d novo(s), %d preço(s) alterado(s), %d descrição(ões) alterada(s), "
                        + "%d inativado(s), %d reativado(s).", carga.getNovos(), carga.getPrecosAlterados(),
                carga.getDescricoesAlteradas(), carga.getInativados(), carga.getReativados()) + errosTexto);

        LojaEntity loja = entityManager.find(LojaEntity.class, carga.getLojaId());
        loja.setHashAplicado(carga.getHash());
    }

    // Linha inválida é ignorada e listada; não derruba a carga (a regra dos 20% cobre o arquivo
    // estragado de verdade).
    Interpretacao interpretar(byte[] arquivo) {
        String texto = new String(arquivo, StandardCharsets.ISO_8859_1);
        List<Item> itens = new ArrayList<>();
        List<String> erros = new ArrayList<>();
        int total = 0;
        String[] linhas = texto.split("\r?\n");
        for (int i = 0; i < linhas.length; i++) {
            String linha = linhas[i].stripTrailing();
            if (linha.isBlank()) {
                continue;
            }
            total++;
            int numero = i + 1;
            if (!linha.endsWith("||")) {
                erros.add("linha " + numero + " não termina com ||");
                continue;
            }
            String[] partes = linha.substring(0, linha.length() - 2).split("\\|", -1);
            if (partes.length != 3) {
                erros.add("linha " + numero + " não tem 3 campos");
                continue;
            }
            String codigo = partes[0].trim();
            String descricao = partes[1].strip();
            String preco = partes[2].trim();
            if (!CODIGO.matcher(codigo).matches()) {
                erros.add("linha " + numero + " com código inválido");
                continue;
            }
            if (descricao.isEmpty()) {
                erros.add("linha " + numero + " sem descrição");
                continue;
            }
            if (!PRECO.matcher(preco).matches() || Long.parseLong(preco) > Integer.MAX_VALUE) {
                erros.add("linha " + numero + " com preço inválido");
                continue;
            }
            if (descricao.length() > MAX_DESCRICAO) {
                descricao = descricao.substring(0, MAX_DESCRICAO).stripTrailing();
            }
            itens.add(new Item(codigo, descricao, Integer.parseInt(preco)));
        }
        return new Interpretacao(itens, total, erros);
    }

    // Supabase grátis tem 500 MB: das cargas antigas fica só o resumo.
    @Scheduled(every = "6h", delayed = "1m")
    @Transactional
    void limparArquivosAntigos() {
        int limpas = entityManager.createNativeQuery(
                        "UPDATE carga_pricetab c SET arquivo = NULL WHERE arquivo IS NOT NULL AND id NOT IN ("
                                + "SELECT id FROM (SELECT id, row_number() OVER (PARTITION BY loja_id ORDER BY id DESC) AS n "
                                + "FROM carga_pricetab) r WHERE r.n <= :manter)")
                .setParameter("manter", CARGAS_COM_ARQUIVO_GUARDADO)
                .executeUpdate();
        if (limpas > 0) {
            Log.infof("Arquivo removido de %d carga(s) antiga(s) do PRICETAB", limpas);
        }
    }

    // ------------------------------------------------------------------------------------------

    public CargaPricetabEntity ultimaCarga(Integer lojaId) {
        return entityManager.createQuery(
                        "SELECT c FROM CargaPricetabEntity c WHERE c.lojaId = :loja ORDER BY c.id DESC",
                        CargaPricetabEntity.class)
                .setParameter("loja", lojaId)
                .setMaxResults(1)
                .getResultStream().findFirst().orElse(null);
    }

    private static Integer inteiro(Object valor) {
        return valor == null ? 0 : ((Number) valor).intValue();
    }

    private CargaRecebidaDTO paraDTO(CargaPricetabEntity carga, String mensagem) {
        return CargaRecebidaDTO.builder()
                .id(carga.getId())
                .situacao(carga.getSituacao())
                .recebidaEm(carga.getRecebidaEm() == null ? null : carga.getRecebidaEm().toString())
                .processadaEm(carga.getProcessadaEm() == null ? null : carga.getProcessadaEm().toString())
                .linhasTotal(carga.getLinhasTotal())
                .linhasInvalidas(carga.getLinhasInvalidas())
                .novos(carga.getNovos())
                .precosAlterados(carga.getPrecosAlterados())
                .descricoesAlteradas(carga.getDescricoesAlteradas())
                .inativados(carga.getInativados())
                .reativados(carga.getReativados())
                .mensagem(mensagem)
                .build();
    }
}
