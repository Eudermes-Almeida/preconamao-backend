package br.com.preconamao.service;

import br.com.preconamao.dto.CargaRecebidaDTO;
import br.com.preconamao.dto.SinalRespostaDTO;
import br.com.preconamao.dto.SituacaoLojaDTO;
import br.com.preconamao.entity.CargaPricetabEntity;
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
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Cargas de preços das lojas. PRICETAB: o agente da loja envia e o servidor guarda na hora (o
// agente não espera o processamento). API: o coletor (ColetaApiService) busca os dados e registra
// a carga do mesmo jeito. Um agendador aplica as cargas pendentes, UMA de cada vez no servidor
// inteiro e só a mais nova de cada loja (regras 11a–11e do multi-loja), cada uma numa transação:
// ou entra inteira, ou nada muda (ver aplicar_carga() no scripts/027).
@ApplicationScoped
public class CargaPricetabService {

    // 27 mil linhas dão ~1,6 MB; 10 MB sobra e barra envio absurdo.
    public static final int TAMANHO_MAXIMO_BYTES = 10 * 1024 * 1024;

    // Acima disso de linhas com erro de ESTRUTURA (código inválido, preço ilegível, campo
    // faltando), o arquivo não segue o formato da loja: carga RETIDA (regra 8c). Abreviação
    // desconhecida, preço 0,00 e código repetido NÃO contam (regras 9, 18, 19).
    private static final double MAXIMO_LINHAS_COM_ERRO = 0.02;
    // Uma carga grande (hipermercado: ~28 mil linhas, primeira carga) passa do tempo padrão de 60 s
    // da transação.
    private static final int TEMPO_MAXIMO_CARGA_S = 600;
    // Arquivos guardados por loja: os 3 últimos recebidos e sempre o último aplicado (regra 10d).
    private static final int ARQUIVOS_GUARDADOS = 3;

    private static final Pattern CODIGO = Pattern.compile("\\d{1,14}");
    private static final Pattern PRECO = Pattern.compile("\\d{1,10}");
    private static final Pattern PRECO_REAIS = Pattern.compile("\\d{1,3}(\\.?\\d{3})*,\\d{2}");
    private static final int MAX_ERROS_NA_MENSAGEM = 20;
    // Nome da cópia enviada pelo agente (regra 7b): PRICETAB_<loja>_<AAAA-MM-DD_HHMM>.TXT.
    private static final Pattern NOME_ARQUIVO = Pattern.compile(
            "(?i)^PRICETAB_([a-z0-9]+(?:-[a-z0-9]+)*)_\\d{4}-\\d{2}-\\d{2}_\\d{4,6}\\.TXT$");
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Inject
    EntityManager entityManager;

    @Inject
    LojaService lojaService;

    @Inject
    ArmazenamentoArquivos armazenamento;

    private final Jsonb jsonb = JsonbBuilder.create();

    // Uma linha do arquivo já interpretada (código como veio da origem).
    record Linha(String codigoOrigem, String descricao, int preco) {
    }

    // Um produto pronto para aplicar: código canônico; semPreco = ZERO / CONFLITO / null.
    record Item(String codigo, String codigoOrigem, String descricao, int preco, String semPreco) {
    }

    // linhasFisicas / linhasFisicasComErro: linhas do ARQUIVO (não registros). Um registro com erro
    // que emendou várias linhas conta todas: 300 linhas de lixo seguidas são 300 linhas com erro,
    // não 1 (senão a regra dos 2% não pegaria o arquivo corrompido).
    record Interpretacao(List<Item> itens, int linhasTotal, List<String> erros, int codigosRepetidos,
                         int conflitosPreco, int semPrecoZero, int linhasFisicas, int linhasFisicasComErro) {
    }

    // ------------------------------------------------------------------------------------------
    // Recebimento
    // ------------------------------------------------------------------------------------------

    @Transactional
    public CargaRecebidaDTO receber(LojaEntity loja, byte[] arquivo, String nomeArquivo) {
        String hash = LojaService.sha256(arquivo);
        LojaEntity lojaAtual = entityManager.find(LojaEntity.class, loja.getId());
        lojaAtual.setUltimoSinalEm(OffsetDateTime.now());
        lojaService.registrarHashInformado(lojaAtual, hash);

        if (hash.equals(lojaAtual.getHashAplicado())) {
            return CargaRecebidaDTO.builder().situacao("JA_APLICADO")
                    .mensagem("Este arquivo já está aplicado.").build();
        }
        // Mesmo arquivo da última carga (ainda na fila, retida ou com erro): não duplica. Só compara
        // com a última — um arquivo que volta a uma versão antiga (preço que foi e voltou) é carga nova.
        CargaPricetabEntity ultima = ultimaCarga(loja.getId());
        if (ultima != null && hash.equals(ultima.getHash())) {
            if (reenfileirarSeLiberada(lojaAtual, ultima)) {
                return paraDTO(ultima, "Carga liberada: reprocessando em alguns segundos.");
            }
            return paraDTO(ultima, "Este arquivo já foi recebido (carga nº " + ultima.getId() + ").");
        }

        CargaPricetabEntity carga = registrar(lojaAtual, CargaPricetabEntity.RECEBIDA, "PRICETAB", nomeArquivo,
                arquivo, hash, ".txt");

        // Regra 7c: o nome da cópia não bate com a loja da chave (configuração trocada na
        // instalação: os preços de uma loja iriam para a outra). Nada é aplicado.
        String lojaDoNome = lojaDoNome(nomeArquivo);
        if (nomeArquivo != null && !Objects.equals(lojaDoNome, lojaAtual.getSlug())) {
            carga.setSituacao(CargaPricetabEntity.RETIDA);
            carga.setProcessadaEm(OffsetDateTime.now());
            carga.setMensagem("RETIDA: o nome do arquivo (" + nomeArquivo + ") não corresponde à loja da chave ("
                    + lojaAtual.getSlug() + "). Nada foi aplicado. Confira a configuração do agente desta loja.");
            Log.warnf("Carga %d da loja %d RETIDA: nome %s x loja %s", carga.getId(), lojaAtual.getId(),
                    nomeArquivo, lojaAtual.getSlug());
            return paraDTO(carga, carga.getMensagem());
        }

        CargaRecebidaDTO dto = paraDTO(carga, "Recebido; processamento em alguns segundos.");
        dto.setNovaCarga(true);
        return dto;
    }

    // ------------------------------------------------------------------------------------------
    // Pacote do agente RPInfo (scripts/034): o agente consulta a API da RPInfo DENTRO da loja e envia
    // um pacote JSON (compactado ou não) com os campos da RPInfo que usamos:
    //   {"tipo": "COMPLETA"|"PARCIAL", "hashFoto": "<sha-256 da foto local>", "departamentos": {...},
    //    "produtos": [...produtos como vieram da RPInfo...], "excluidos": [códigos internos]}
    // A conversão é a mesma da coleta pelo servidor (ColetaRpinfo.converter): uma regra corrigida
    // aqui vale para todas as lojas sem reinstalar agentes. hashFoto vira o hash da carga: é o que o
    // agente informa no sinal (proteção de preço) e o que evita aplicar duas vezes o mesmo estado.
    // ------------------------------------------------------------------------------------------

    public record PacoteInvalido(int status, String mensagem) {
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public Object receberPacoteRpinfo(LojaEntity loja, byte[] corpo) {
        LojaEntity lojaAtual = entityManager.find(LojaEntity.class, loja.getId());
        FormatoPricetab formato = FormatoPricetab.de(lojaService.formato(lojaAtual));
        boolean view = FormatoPricetab.DIALETO_VIEW.equals(formato.dialeto());
        if (!LojaEntity.ORIGEM_API.equals(lojaAtual.getTipoOrigem()) || !FormatoPricetab.COLETA_AGENTE.equals(formato.coleta())
                || !(view || FormatoPricetab.DIALETO_RPINFO.equals(formato.dialeto()))) {
            return new PacoteInvalido(409, "Loja não configurada para o agente (formato \"API RPInfo (agente)\" ou \"Banco de dados (agente)\").");
        }
        Map<String, Object> pacote;
        try {
            pacote = jsonb.fromJson(new String(descompactar(corpo), StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            return new PacoteInvalido(400, "Pacote ilegível (esperado JSON, compactado em gzip ou não).");
        }
        String tipo = String.valueOf(pacote.get("tipo"));
        String hashFoto = pacote.get("hashFoto") == null ? "" : pacote.get("hashFoto").toString();
        if (!CargaPricetabEntity.COMPLETA.equals(tipo) && !CargaPricetabEntity.PARCIAL.equals(tipo)) {
            return new PacoteInvalido(400, "tipo deve ser COMPLETA ou PARCIAL");
        }
        if (!hashFoto.matches("[0-9a-f]{64}")) {
            return new PacoteInvalido(400, "hashFoto deve ser o SHA-256 (64 caracteres hexadecimais) da foto local");
        }

        lojaAtual.setUltimoSinalEm(OffsetDateTime.now());
        lojaService.registrarHashInformado(lojaAtual, hashFoto);
        if (CargaPricetabEntity.COMPLETA.equals(tipo)) {
            lojaAtual.setPedirCompleta(false);
            lojaAtual.setUltimaColetaCompletaEm(OffsetDateTime.now());
        }
        if (hashFoto.equals(lojaAtual.getHashAplicado())) {
            return CargaRecebidaDTO.builder().situacao("JA_APLICADO").mensagem("Esta foto da loja já está aplicada.").build();
        }
        CargaPricetabEntity ultima = ultimaCarga(loja.getId());
        if (ultima != null && hashFoto.equals(ultima.getHash())) {
            if (reenfileirarSeLiberada(lojaAtual, ultima)) {
                return paraDTO(ultima, "Carga liberada: reprocessando em alguns segundos.");
            }
            return paraDTO(ultima, "Este pacote já foi recebido (carga nº " + ultima.getId() + ").");
        }

        Map<String, String> departamentos = new LinkedHashMap<>();
        if (pacote.get("departamentos") instanceof Map<?, ?> deptos) {
            deptos.forEach((codigo, nome) -> departamentos.put(String.valueOf(codigo), nome == null ? null : nome.toString()));
        }
        Map<String, Map<String, Object>> porCodigo = new LinkedHashMap<>();
        ColetaRpinfo.Contagem contagem = new ColetaRpinfo.Contagem();
        List<String> internos = new ArrayList<>();
        LocalDate hoje = LocalDate.now(ColetaRpinfo.FUSO_ERP);
        if (view) {
            // Conector de banco: "linhas" = as linhas da VIEW padrão como o banco devolveu.
            for (Object item : (List<Object>) pacote.getOrDefault("linhas", List.of())) {
                if (item instanceof Map<?, ?> linha) {
                    internos.add(ColetaViewPadrao.interno(linha));
                    ColetaViewPadrao.converter((Map<String, Object>) linha, hoje, porCodigo, contagem);
                }
            }
            for (Object excluido : (List<Object>) pacote.getOrDefault("excluidos", List.of())) {
                internos.add(ColetaViewPadrao.interno(excluido));
            }
        } else {
            for (Object item : (List<Object>) pacote.getOrDefault("produtos", List.of())) {
                if (item instanceof Map<?, ?> produto) {
                    internos.add(ColetaRpinfo.interno(produto.get("Codigo")));
                    ColetaRpinfo.converter((Map<String, Object>) produto, departamentos, hoje, porCodigo, contagem);
                }
            }
            for (Object excluido : (List<Object>) pacote.getOrDefault("excluidos", List.of())) {
                internos.add(ColetaRpinfo.interno(excluido));
            }
        }
        List<Map<String, Object>> itens = ColetaApiService.ordenados(porCodigo);
        boolean completa = CargaPricetabEntity.COMPLETA.equals(tipo);
        if (completa && itens.isEmpty()) {
            return new PacoteInvalido(400, "Pacote COMPLETO sem nenhum produto válido.");
        }
        byte[] conteudo = jsonb.toJson(completa ? itens : Map.of("itens", itens, "internos", internos)).getBytes(StandardCharsets.UTF_8);
        CargaPricetabEntity carga = registrar(lojaAtual, CargaPricetabEntity.RECEBIDA, LojaEntity.ORIGEM_API,
                null, conteudo, hashFoto, ".json");
        carga.setTipo(tipo);
        Log.infof("Pacote %s do agente (" + formato.dialeto() + ") da loja %d: %d produto(s) do ERP -> %d código(s); carga nº %d na fila",
                tipo, loja.getId(), contagem.produtos, itens.size(), carga.getId());
        CargaRecebidaDTO dto = paraDTO(carga, "Recebido; processamento em alguns segundos.");
        dto.setNovaCarga(true);
        return dto;
    }

    static byte[] descompactar(byte[] corpo) throws java.io.IOException {
        if (corpo.length > 2 && (corpo[0] & 0xff) == 0x1f && (corpo[1] & 0xff) == 0x8b) {
            try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(corpo))) {
                return gz.readNBytes(TAMANHO_MAXIMO_BYTES * 5);
            }
        }
        return corpo;
    }

    // Grava o arquivo no armazenamento e a ficha da carga no banco.
    public CargaPricetabEntity registrar(LojaEntity loja, String situacao, String origem, String nomeArquivo,
                                         byte[] conteudo, String hash, String extensao) {
        String nomeGuardado = OffsetDateTime.now().format(CARIMBO) + "_" + hash.substring(0, 12) + extensao;
        CargaPricetabEntity carga = CargaPricetabEntity.builder()
                .lojaId(loja.getId())
                .recebidaEm(OffsetDateTime.now())
                .hash(hash)
                .tamanhoBytes(conteudo.length)
                .origem(origem)
                .nomeArquivo(nomeArquivo)
                .caminhoArquivo(armazenamento.salvar(loja.getId(), nomeGuardado, conteudo))
                .situacao(situacao)
                .build();
        entityManager.persist(carga);
        return carga;
    }

    // Regra 26b: a carga ficou RETIDA pela regra dos 20% (ou pelo formato) e alguém ligou "liberar
    // a próxima carga" (troca de sistema da loja): o agente não reenvia o mesmo arquivo (já foi
    // recebido), então a própria carga retida volta para a fila. A retida por nome que não confere
    // com a chave (regra 7c) NUNCA volta: é configuração errada na loja.
    private boolean reenfileirarSeLiberada(LojaEntity loja, CargaPricetabEntity carga) {
        boolean nomeConfere = carga.getNomeArquivo() == null || Objects.equals(lojaDoNome(carga.getNomeArquivo()), loja.getSlug());
        if (!loja.isLiberarProximaCarga() || !CargaPricetabEntity.RETIDA.equals(carga.getSituacao()) || !nomeConfere
                || carga.getCaminhoArquivo() == null) {
            return false;
        }
        carga.setSituacao(CargaPricetabEntity.RECEBIDA);
        carga.setProcessadaEm(null);
        carga.setMensagem(null);
        Log.infof("Carga %d da loja %d liberada: volta para a fila", carga.getId(), loja.getId());
        return true;
    }

    static String lojaDoNome(String nomeArquivo) {
        if (nomeArquivo == null) {
            return null;
        }
        Matcher m = NOME_ARQUIVO.matcher(nomeArquivo.trim());
        return m.matches() ? m.group(1).toLowerCase() : null;
    }

    // Sinal de vida do agente (a cada minuto). enviarArquivo = o servidor não tem o arquivo que o
    // agente tem (envio perdido): o agente manda de novo.
    @Transactional
    public SinalRespostaDTO registrarSinal(LojaEntity loja, String hashArquivo) {
        LojaEntity lojaAtual = entityManager.find(LojaEntity.class, loja.getId());
        lojaAtual.setUltimoSinalEm(OffsetDateTime.now());
        lojaService.registrarHashInformado(lojaAtual, hashArquivo);

        CargaPricetabEntity ultima = ultimaCarga(loja.getId());
        if (ultima != null && Objects.equals(hashArquivo, ultima.getHash())) {
            reenfileirarSeLiberada(lojaAtual, ultima);
        }
        boolean enviarArquivo = hashArquivo != null
                && !hashArquivo.equals(lojaAtual.getHashAplicado())
                && (ultima == null || !hashArquivo.equals(ultima.getHash()));
        return SinalRespostaDTO.builder()
                .enviarArquivo(enviarArquivo)
                .fazerCompleta(lojaAtual.isPedirCompleta())
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
        return SituacaoLojaDTO.builder()
                .loja(loja.getNome())
                .precoConfiavel(lojaService.situacaoPreco(loja).confiavel())
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
                processada = QuarkusTransaction.requiringNew().timeout(TEMPO_MAXIMO_CARGA_S).call(this::processarProxima);
            } catch (FalhaCarga falha) {
                Log.errorf(falha.getCause(), "Carga %d falhou", falha.cargaId);
                QuarkusTransaction.requiringNew().run(() -> marcarErro(falha.cargaId, falha.getCause()));
                continue;
            } catch (Exception e) {
                Log.error("Falha inesperada ao processar carga", e);
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

    // Pega a carga pendente mais nova e aplica, tudo na mesma transação: se o banco rejeitar
    // qualquer coisa, nada da carga fica gravado. Devolve o id processado ou null.
    // COMPLETA: as pendentes mais antigas da mesma loja ficam IGNORADAS (ela já traz o estado atual).
    // PARCIAL (coleta incremental): não substitui as anteriores — se houver pendentes mais antigas
    // da mesma loja, aplica primeiro a mais antiga (as mudanças entram na ordem em que vieram).
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
        if (CargaPricetabEntity.PARCIAL.equals(carga.getTipo())) {
            @SuppressWarnings("unchecked")
            List<Number> maisAntiga = entityManager.createNativeQuery(
                            "SELECT id FROM carga_pricetab WHERE situacao = 'RECEBIDA' AND loja_id = :loja "
                                    + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
                    .setParameter("loja", carga.getLojaId())
                    .getResultList();
            if (!maisAntiga.isEmpty()) {
                carga = entityManager.find(CargaPricetabEntity.class, maisAntiga.get(0).longValue());
            }
        }
        try {
            if (CargaPricetabEntity.COMPLETA.equals(carga.getTipo())) {
                entityManager.createQuery(
                                "UPDATE CargaPricetabEntity c SET c.situacao = :ignorada, c.processadaEm = :agora, "
                                        + "c.mensagem = :mensagem WHERE c.lojaId = :loja AND c.situacao = :recebida AND c.id < :id")
                        .setParameter("ignorada", CargaPricetabEntity.IGNORADA)
                        .setParameter("agora", OffsetDateTime.now())
                        .setParameter("mensagem", "IGNORADA – substituída pela carga nº " + carga.getId() + ", mais nova.")
                        .setParameter("loja", carga.getLojaId())
                        .setParameter("recebida", CargaPricetabEntity.RECEBIDA)
                        .setParameter("id", carga.getId())
                        .executeUpdate();
            }
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

    @SuppressWarnings("unchecked")
    void aplicar(CargaPricetabEntity carga) {
        LojaEntity loja = entityManager.find(LojaEntity.class, carga.getLojaId());
        byte[] conteudo = carga.getCaminhoArquivo() != null ? armazenamento.ler(carga.getCaminhoArquivo()) : carga.getArquivo();
        carga.setProcessadaEm(OffsetDateTime.now());

        List<Map<String, Object>> itens;
        List<Object> internos = null;
        String errosTexto = "";
        boolean parcial = CargaPricetabEntity.PARCIAL.equals(carga.getTipo());
        if (parcial) {
            // Coleta incremental: {"itens": [...formato padrão...], "internos": [códigos do ERP afetados]}.
            Map<String, Object> pacote = jsonb.fromJson(new String(conteudo, StandardCharsets.UTF_8), Map.class);
            itens = (List<Map<String, Object>>) pacote.getOrDefault("itens", List.of());
            internos = (List<Object>) pacote.getOrDefault("internos", List.of());
            carga.setLinhasTotal(itens.size());
            carga.setLinhasInvalidas(0);
            carga.setLinhasSemPreco((int) itens.stream().filter(i -> i.get("semPreco") != null).count());
            carga.setCodigosRepetidos(0);
            carga.setConflitosPreco(0);
        } else if (LojaEntity.ORIGEM_API.equals(carga.getOrigem())) {
            // A coleta já entrega os itens no formato padrão (ColetaApiService).
            itens = jsonb.fromJson(new String(conteudo, StandardCharsets.UTF_8), List.class);
            carga.setLinhasTotal(itens.size());
            carga.setLinhasInvalidas(0);
            carga.setLinhasSemPreco((int) itens.stream().filter(i -> i.get("semPreco") != null).count());
            carga.setCodigosRepetidos(0);
            carga.setConflitosPreco(0);
        } else {
            Interpretacao interpretacao = interpretar(conteudo, FormatoPricetab.de(lojaService.formato(loja)));
            carga.setLinhasTotal(interpretacao.linhasTotal());
            carga.setLinhasInvalidas(interpretacao.erros().size());
            carga.setLinhasSemPreco(interpretacao.semPrecoZero());
            carga.setCodigosRepetidos(interpretacao.codigosRepetidos());
            carga.setConflitosPreco(interpretacao.conflitosPreco());
            errosTexto = interpretacao.erros().isEmpty() ? "" : "\nLinhas ignoradas: "
                    + String.join("; ", interpretacao.erros().subList(0, Math.min(MAX_ERROS_NA_MENSAGEM, interpretacao.erros().size())))
                    + (interpretacao.erros().size() > MAX_ERROS_NA_MENSAGEM ? " ..." : "");

            if (interpretacao.linhasFisicas() > 0
                    && (double) interpretacao.linhasFisicasComErro() / interpretacao.linhasFisicas() > MAXIMO_LINHAS_COM_ERRO) {
                carga.setSituacao(CargaPricetabEntity.RETIDA);
                carga.setMensagem(String.format("RETIDA: %d de %d linhas do arquivo (%s) não seguem o formato cadastrado "
                                + "da loja (limite: 2%%). Arquivo corrompido, ou a loja trocou de sistema? Nada foi alterado.",
                        interpretacao.linhasFisicasComErro(), interpretacao.linhasFisicas(),
                        percentual((double) interpretacao.linhasFisicasComErro() / interpretacao.linhasFisicas())) + errosTexto);
                return;
            }
            itens = interpretacao.itens().stream().map(item -> {
                Map<String, Object> mapa = new LinkedHashMap<>();
                mapa.put("codigo", item.codigo());
                mapa.put("codigoOrigem", item.codigoOrigem());
                mapa.put("descricao", item.descricao());
                mapa.put("preco", item.preco());
                mapa.put("semPreco", item.semPreco());
                return mapa;
            }).toList();
        }

        boolean liberada = loja.isLiberarProximaCarga();
        String resultadoJson = (String) entityManager.createNativeQuery(
                        "SELECT CAST(aplicar_carga(:loja, CAST(:itens AS jsonb), :limite, :forcar, CAST(:internos AS jsonb)) AS text)")
                .setParameter("loja", loja.getId())
                .setParameter("itens", jsonb.toJson(itens))
                .setParameter("limite", loja.getLimiteInativacao())
                .setParameter("forcar", liberada)
                // Completa = JSON null (texto, para o parâmetro nunca ir como SQL NULL sem tipo).
                .setParameter("internos", internos == null ? "null" : jsonb.toJson(internos.stream().map(String::valueOf).toList()))
                .getSingleResult();
        Map<String, Object> resultado = jsonb.fromJson(resultadoJson, Map.class);

        if (Boolean.TRUE.equals(resultado.get("retida")) && "QUEDA_PRECO".equals(resultado.get("motivo"))) {
            int quedas = inteiro(resultado.get("quedas"));
            int ativos = inteiro(resultado.get("ativos"));
            carga.setSituacao(CargaPricetabEntity.RETIDA);
            carga.setMensagem(String.format("RETIDA por segurança: %d de %d produtos (%s) teriam o preço derrubado em mais de %s "
                            + "de uma vez. Limite da loja: %s dos produtos. Erro na origem ou dados adulterados? Conferir e, "
                            + "se estiver certo, liberar a próxima carga. Nada foi alterado.", quedas, ativos,
                    percentual(ativos == 0 ? 0 : (double) quedas / ativos),
                    percentual(new BigDecimal(String.valueOf(resultado.get("quedaMinima"))).doubleValue()),
                    percentual(new BigDecimal(String.valueOf(resultado.get("quedaLimite"))).doubleValue())) + errosTexto);
            return;
        }
        if (Boolean.TRUE.equals(resultado.get("retida"))) {
            int sumiriam = inteiro(resultado.get("sumiriam"));
            int ativos = inteiro(resultado.get("ativos"));
            carga.setSituacao(CargaPricetabEntity.RETIDA);
            carga.setMensagem((ativos == 0 && inteiro(resultado.get("itens")) == 0
                    ? "RETIDA: o arquivo não tem nenhum produto válido. Nada foi alterado."
                    : String.format("RETIDA por segurança: desativaria %d de %d produtos (%s). Limite da loja: %s. "
                                    + "Arquivo cortado, ou a loja trocou de sistema (nesse caso, liberar a próxima carga). "
                                    + "Nada foi alterado.", sumiriam, ativos,
                            percentual(ativos == 0 ? 0 : (double) sumiriam / ativos),
                            percentual(loja.getLimiteInativacao().doubleValue()))) + errosTexto);
            return;
        }

        carga.setSituacao(CargaPricetabEntity.CONCLUIDA);
        carga.setNovos(inteiro(resultado.get("novos")));
        carga.setPrecosAlterados(inteiro(resultado.get("precosAlterados")));
        carga.setDescricoesAlteradas(inteiro(resultado.get("descricoesAlteradas")));
        carga.setInativados(inteiro(resultado.get("inativados")));
        carga.setReativados(inteiro(resultado.get("reativados")));
        String extras = (carga.getLinhasSemPreco() > 0 ? String.format(" %d sem preço (0,00).", carga.getLinhasSemPreco()) : "")
                + (carga.getCodigosRepetidos() > 0 ? String.format(" %d código(s) repetido(s), %d com preços diferentes "
                + "(ficam sem preço).", carga.getCodigosRepetidos(), carga.getConflitosPreco()) : "")
                + (liberada ? " Carga LIBERADA manualmente" + (loja.getLiberadaPor() == null ? "" : " por " + loja.getLiberadaPor())
                + (loja.getLiberadaEm() == null ? "" : " em " + loja.getLiberadaEm()) + "." : "");
        carga.setMensagem((parcial ? "Parcial (" + internos.size() + " produto(s) do ERP): " : "")
                + String.format("%d novo(s), %d preço(s) alterado(s), %d descrição(ões) alterada(s), "
                        + "%d inativado(s), %d reativado(s).", carga.getNovos(), carga.getPrecosAlterados(),
                carga.getDescricoesAlteradas(), carga.getInativados(), carga.getReativados()) + extras + errosTexto);

        if (liberada) {
            loja.setLiberarProximaCarga(false);
        }
        loja.setHashAplicado(carga.getHash());
        lojaService.atualizarDivergencia(loja);
    }

    private static String percentual(double fracao) {
        return BigDecimal.valueOf(fracao * 100).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    // Lê o arquivo conforme a ficha da loja. Linha com erro de estrutura é ignorada e listada; não
    // derruba a carga sozinha (o limite de 2% e a regra dos 20% cobrem o arquivo estragado).
    Interpretacao interpretar(byte[] arquivo, FormatoPricetab formato) {
        String texto = new String(arquivo, formato.codificacao());
        String sep = Pattern.quote(formato.separador());
        // Começo de registro: a linha que não começa assim é continuação da anterior (algumas
        // descrições vêm cortadas por uma quebra de linha no meio).
        Pattern inicioRegistro = Pattern.compile("^\\d{1,14}" + sep + ".*");
        Pattern registroCompleto = Pattern.compile("^\\d{1,14}" + sep + ".*" + sep + "[\\d.,]+" + sep + "(" + sep + ")?$");

        List<Linha> linhasValidas = new ArrayList<>();
        List<String> erros = new ArrayList<>();
        int total = 0;
        int fisicas = 0;
        int fisicasComErro = 0;
        String[] linhas = texto.split("\r?\n");
        for (int i = 0; i < linhas.length; i++) {
            String linha = linhas[i].stripTrailing();
            if (linha.isBlank()) {
                continue;
            }
            total++;
            int numero = i + 1;
            int inicio = i;
            // Registro cortado por quebra de linha: emenda as linhas seguintes que não começam um
            // registro novo (a quebra cai no meio da descrição, então emenda sem espaço).
            while (formato.emendarLinhas() && !registroCompleto.matcher(linha).matches() && i + 1 < linhas.length
                    && !linhas[i + 1].isBlank() && !inicioRegistro.matcher(linhas[i + 1]).matches()) {
                linha = linha + linhas[++i].stripTrailing();
            }
            int consumidas = i - inicio + 1;
            fisicas += consumidas;
            String fim = formato.separador();
            String semFinal = linha.endsWith(fim + fim) ? linha.substring(0, linha.length() - 2 * fim.length())
                    : linha.endsWith(fim) ? linha.substring(0, linha.length() - fim.length()) : null;
            if (semFinal == null) {
                erros.add("linha " + numero + " não termina com " + fim);
                fisicasComErro += consumidas;
                continue;
            }
            String[] partes = semFinal.split(sep, -1);
            if (partes.length != 3) {
                erros.add("linha " + numero + " não tem 3 campos");
                fisicasComErro += consumidas;
                continue;
            }
            String codigo = partes[0].trim();
            String descricao = partes[1].strip();
            Integer preco = precoEmCentavos(partes[2].trim());
            if (!CODIGO.matcher(codigo).matches()) {
                erros.add("linha " + numero + " com código inválido");
                fisicasComErro += consumidas;
                continue;
            }
            if (descricao.isEmpty()) {
                erros.add("linha " + numero + " sem descrição");
                fisicasComErro += consumidas;
                continue;
            }
            if (preco == null) {
                erros.add("linha " + numero + " com preço inválido");
                fisicasComErro += consumidas;
                continue;
            }
            if (descricao.length() > formato.tamanhoDescricao()) {
                descricao = descricao.substring(0, formato.tamanhoDescricao()).stripTrailing();
            }
            linhasValidas.add(new Linha(codigo, descricao, preco));
        }
        Interpretacao resolvida = resolverRepetidos(linhasValidas, total, erros);
        return new Interpretacao(resolvida.itens(), total, erros, resolvida.codigosRepetidos(), resolvida.conflitosPreco(),
                resolvida.semPrecoZero(), fisicas, fisicasComErro);
    }

    // Código repetido no arquivo, já padronizado (regras 17 e 19): preço igual = um só; um com
    // preço e outro 0,00 = o que tem preço; preços diferentes acima de zero = SEM PREÇO (não dá
    // para saber qual a loja cobra), com a descrição da última linha.
    static Interpretacao resolverRepetidos(List<Linha> linhas, int total, List<String> erros) {
        Map<String, List<Linha>> porCodigo = new LinkedHashMap<>();
        for (Linha linha : linhas) {
            porCodigo.computeIfAbsent(CodigoBarras.canonico(linha.codigoOrigem()), c -> new ArrayList<>()).add(linha);
        }
        List<Item> itens = new ArrayList<>();
        int repetidos = 0;
        int conflitos = 0;
        int zeros = 0;
        for (Map.Entry<String, List<Linha>> entrada : porCodigo.entrySet()) {
            List<Linha> doCodigo = entrada.getValue();
            Linha escolhida = doCodigo.get(doCodigo.size() - 1);
            String semPreco = null;
            if (doCodigo.size() > 1) {
                repetidos++;
                List<Integer> precosPositivos = doCodigo.stream().map(Linha::preco).filter(p -> p > 0).distinct().toList();
                if (precosPositivos.size() == 1) {
                    int preco = precosPositivos.get(0);
                    escolhida = doCodigo.stream().filter(l -> l.preco() == preco).reduce((a, b) -> b).orElseThrow();
                } else if (precosPositivos.size() > 1) {
                    semPreco = ProdutoEntity.SEM_PRECO_CONFLITO;
                    conflitos++;
                }
            }
            if (semPreco == null && escolhida.preco() == 0) {
                semPreco = ProdutoEntity.SEM_PRECO_ZERO;
                zeros++;
            }
            itens.add(new Item(entrada.getKey(), escolhida.codigoOrigem(), escolhida.descricao(), escolhida.preco(), semPreco));
        }
        return new Interpretacao(itens, total, erros, repetidos, conflitos, zeros, total, 0);
    }

    // "0000000389" (centavos, arquivo simulado) ou "3,89" / "1.234,56" (reais, arquivo real).
    static Integer precoEmCentavos(String preco) {
        long centavos;
        if (PRECO.matcher(preco).matches()) {
            centavos = Long.parseLong(preco);
        } else if (PRECO_REAIS.matcher(preco).matches()) {
            centavos = Long.parseLong(preco.replace(".", "").replace(",", ""));
        } else {
            return null;
        }
        return centavos > Integer.MAX_VALUE ? null : (int) centavos;
    }

    // Arquivos guardados (regra 10d): por loja, os 3 últimos recebidos e sempre o último aplicado.
    @Scheduled(every = "10m", delayed = "1m")
    @Transactional
    void limparArquivosAntigos() {
        @SuppressWarnings("unchecked")
        List<Object[]> antigos = entityManager.createNativeQuery(
                        "SELECT id, caminho_arquivo FROM ("
                                + "  SELECT c.id, c.caminho_arquivo, row_number() OVER (PARTITION BY c.loja_id ORDER BY c.id DESC) AS n, "
                                + "         c.id = (SELECT max(a.id) FROM carga_pricetab a WHERE a.loja_id = c.loja_id "
                                + "                 AND a.situacao = 'CONCLUIDA') AS ultima_aplicada "
                                + "    FROM carga_pricetab c WHERE c.caminho_arquivo IS NOT NULL) r "
                                + "WHERE r.n > :manter AND NOT r.ultima_aplicada")
                .setParameter("manter", ARQUIVOS_GUARDADOS)
                .getResultList();
        for (Object[] antigo : antigos) {
            armazenamento.apagar((String) antigo[1]);
            entityManager.createNativeQuery("UPDATE carga_pricetab SET caminho_arquivo = NULL WHERE id = :id")
                    .setParameter("id", ((Number) antigo[0]).longValue())
                    .executeUpdate();
        }
        if (!antigos.isEmpty()) {
            Log.infof("Arquivo removido de %d carga(s) antiga(s)", antigos.size());
        }
    }

    // Regra 9d: o dicionário de uma loja mudou (trigger em abreviacao): recalcula descrições,
    // setor e pré-lista dela sem esperar a próxima carga.
    @Scheduled(every = "10s", delayed = "20s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reprocessarDicionarios() {
        @SuppressWarnings("unchecked")
        List<Number> lojas = QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                "SELECT id FROM loja WHERE reprocessar_dicionario ORDER BY id").getResultList());
        for (Number loja : lojas) {
            try {
                Number alterados = QuarkusTransaction.requiringNew().timeout(TEMPO_MAXIMO_CARGA_S).call(() -> (Number) entityManager.createNativeQuery(
                                "SELECT reprocessar_descricoes(:loja)")
                        .setParameter("loja", loja.intValue())
                        .getSingleResult());
                Log.infof("Dicionário alterado: loja %d reprocessada (%d produto(s) com descrição nova)", loja.intValue(),
                        alterados.intValue());
            } catch (Exception e) {
                Log.errorf(e, "Falha ao reprocessar o dicionário da loja %d", loja.intValue());
            }
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

    private static int inteiro(Object valor) {
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
