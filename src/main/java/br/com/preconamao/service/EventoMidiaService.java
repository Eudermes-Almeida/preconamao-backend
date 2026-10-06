package br.com.preconamao.service;

import br.com.preconamao.dto.EventoMidiaDTO;
import br.com.preconamao.dto.EventoRecenteDTO;
import br.com.preconamao.dto.InstalacaoAppDTO;
import br.com.preconamao.dto.LoteEventosDTO;
import br.com.preconamao.dto.RelatorioInstalacoesDTO;
import br.com.preconamao.dto.RelatorioMidiasDTO;
import br.com.preconamao.dto.RelatorioOfertaDTO;
import br.com.preconamao.entity.EventoMidiaEntity;
import br.com.preconamao.entity.InstalacaoAppEntity;
import br.com.preconamao.entity.LojaEntity;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

// Eventos das ofertas enviados pelo app (POST /eventos) e o relatório da aba administrativa
// (GET /relatorios/midias), mais as instalações do app (POST /eventos/instalacao). Ver
// scripts/018_eventos_midia.sql e 019_instalacao_app.sql.
@ApplicationScoped
public class EventoMidiaService {

    public static final int MAX_EVENTOS_POR_LOTE = 50;

    // Proteção contra envio abusivo (o POST é aberto, o cliente não faz login): um aparelho não
    // registra mais que isto a cada 10 minutos. Uso normal fica muito abaixo (a tela Ofertas inteira
    // rende 10 exibições por abertura; um anúncio, 1 por consulta).
    private static final int MAX_EVENTOS_POR_APARELHO = 300;
    private static final int JANELA_APARELHO_MIN = 10;

    private static final int EVENTOS_RECENTES = 50;
    private static final ZoneId FUSO_LOJA = ZoneId.of("America/Sao_Paulo");

    private static final Set<String> TIPOS = Set.of(EventoMidiaEntity.EXIBICAO, EventoMidiaEntity.FAVORITAR,
            EventoMidiaEntity.DESFAVORITAR, EventoMidiaEntity.LOCALIZAR, EventoMidiaEntity.PRE_LISTA);
    private static final Set<String> ORIGENS = Set.of(EventoMidiaEntity.ANUNCIO, EventoMidiaEntity.TELA_OFERTAS);
    private static final Pattern CODIGO = Pattern.compile("\\d{8,14}");
    private static final Set<String> ORIGENS_INSTALACAO = Set.of(InstalacaoAppEntity.BOTAO, InstalacaoAppEntity.NAVEGADOR);
    private static final Set<String> PLATAFORMAS = Set.of(InstalacaoAppEntity.ANDROID, InstalacaoAppEntity.IOS, InstalacaoAppEntity.OUTRA);

    // Períodos do filtro do painel: quantos dias contam, incluindo hoje.
    private static final Map<String, Integer> PERIODOS = Map.of("hoje", 1, "7dias", 7, "30dias", 30);

    @ConfigProperty(name = "loja.id-padrao", defaultValue = "1")
    Integer lojaPadraoId;

    @Inject
    EntityManager entityManager;

    @Inject
    FamiliaService familiaService;

    public static boolean periodoValido(String periodo) {
        return PERIODOS.containsKey(periodo);
    }

    // ------------------------------------------------------------------------------------------
    // Recebimento
    // ------------------------------------------------------------------------------------------

    // Grava os eventos válidos do lote e devolve quantos entraram. Evento com tipo, origem ou código
    // inválido é descartado sozinho (o resto do lote entra); aparelho acima do limite perde o excesso.
    @Transactional
    public int registrar(LoteEventosDTO lote) {
        UUID aparelho = lerUuid(lote.getAparelhoId());
        if (aparelho == null || lote.getEventos() == null) {
            return 0;
        }
        OffsetDateTime agora = OffsetDateTime.now();
        long recentes = entityManager.createQuery(
                        "SELECT COUNT(e) FROM EventoMidiaEntity e WHERE e.aparelhoId = :aparelho AND e.registradoEm >= :desde", Long.class)
                .setParameter("aparelho", aparelho)
                .setParameter("desde", agora.minusMinutes(JANELA_APARELHO_MIN))
                .getSingleResult();
        long vagas = MAX_EVENTOS_POR_APARELHO - recentes;

        Integer loja = lojaDoApp(lote.getLojaId());
        int gravados = 0;
        for (EventoMidiaDTO evento : lote.getEventos()) {
            if (gravados >= vagas) {
                Log.warnf("Eventos de mídia: aparelho %s passou do limite de %d em %d min; excesso descartado.",
                        aparelho, MAX_EVENTOS_POR_APARELHO, JANELA_APARELHO_MIN);
                break;
            }
            if (evento == null || !TIPOS.contains(evento.getTipo()) || !ORIGENS.contains(evento.getOrigem())
                    || evento.getCodigoBarras() == null || !CODIGO.matcher(evento.getCodigoBarras()).matches()) {
                continue;
            }
            entityManager.persist(EventoMidiaEntity.builder()
                    .lojaId(loja)
                    .tipo(evento.getTipo())
                    .origem(evento.getOrigem())
                    .codigoBarras(evento.getCodigoBarras())
                    .aparelhoId(aparelho)
                    .registradoEm(agora)
                    .build());
            gravados++;
        }
        return gravados;
    }

    // App instalado na tela inicial (POST /eventos/instalacao). Uma vez por aparelho: repetição é
    // ignorada. Devolve false se o corpo for inválido.
    @Transactional
    public boolean registrarInstalacao(InstalacaoAppDTO instalacao) {
        UUID aparelho = lerUuid(instalacao.getAparelhoId());
        if (aparelho == null || !ORIGENS_INSTALACAO.contains(instalacao.getOrigem())
                || !PLATAFORMAS.contains(instalacao.getPlataforma())) {
            return false;
        }
        long jaRegistrado = entityManager.createQuery(
                        "SELECT COUNT(i) FROM InstalacaoAppEntity i WHERE i.lojaId = :loja AND i.aparelhoId = :aparelho", Long.class)
                .setParameter("loja", lojaDoApp(instalacao.getLojaId()))
                .setParameter("aparelho", aparelho)
                .getSingleResult();
        if (jaRegistrado == 0) {
            entityManager.persist(InstalacaoAppEntity.builder()
                    .lojaId(lojaDoApp(instalacao.getLojaId()))
                    .aparelhoId(aparelho)
                    .origem(instalacao.getOrigem())
                    .plataforma(instalacao.getPlataforma())
                    .registradoEm(OffsetDateTime.now())
                    .build());
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Relatório
    // ------------------------------------------------------------------------------------------

    // Relatório de uma ou mais lojas (chave da loja = ela; da rede = as da rede; geral = todas —
    // regra 6 do multi-loja). A Família não tem loja: só entra com a chave geral (comFamilia).
    @Transactional
    public RelatorioMidiasDTO relatorio(List<Integer> lojas, String periodo, boolean comFamilia) {
        OffsetDateTime ate = OffsetDateTime.now();
        OffsetDateTime de = LocalDate.now(FUSO_LOJA).minusDays(PERIODOS.get(periodo) - 1L)
                .atStartOfDay(FUSO_LOJA).toOffsetDateTime();

        Map<String, RelatorioOfertaDTO> porCodigo = new HashMap<>();

        // Contagem por oferta, tipo e origem: eventos e aparelhos distintos.
        List<Object[]> porOrigem = entityManager.createQuery(
                        "SELECT e.codigoBarras, e.tipo, e.origem, COUNT(e), COUNT(DISTINCT e.aparelhoId) "
                                + "FROM EventoMidiaEntity e WHERE e.lojaId IN :lojas AND e.registradoEm >= :de "
                                + "GROUP BY e.codigoBarras, e.tipo, e.origem", Object[].class)
                .setParameter("lojas", lojas)
                .setParameter("de", de)
                .getResultList();
        for (Object[] linha : porOrigem) {
            RelatorioOfertaDTO oferta = porCodigo.computeIfAbsent((String) linha[0], this::linhaVazia);
            boolean anuncio = EventoMidiaEntity.ANUNCIO.equals(linha[2]);
            long eventos = (Long) linha[3];
            long aparelhos = (Long) linha[4];
            switch ((String) linha[1]) {
                case EventoMidiaEntity.EXIBICAO -> {
                    oferta.setExibicoes(oferta.getExibicoes() + eventos);
                    if (anuncio) oferta.setExibicoesAnuncio(eventos); else oferta.setExibicoesTela(eventos);
                }
                case EventoMidiaEntity.LOCALIZAR -> {
                    oferta.setLocalizar(oferta.getLocalizar() + eventos);
                    if (anuncio) oferta.setLocalizarAnuncio(eventos); else oferta.setLocalizarTela(eventos);
                }
                case EventoMidiaEntity.PRE_LISTA -> {
                    if (anuncio) oferta.setPreListaAnuncio(aparelhos); else oferta.setPreListaTela(aparelhos);
                }
                default -> {
                    // FAVORITAR/DESFAVORITAR: só aparelhos distintos, na consulta abaixo.
                }
            }
        }

        // Aparelhos distintos por oferta e tipo, somando as origens (o mesmo aparelho que incluiu
        // pela tela e pelo anúncio conta uma vez).
        List<Object[]> distintos = entityManager.createQuery(
                        "SELECT e.codigoBarras, e.tipo, COUNT(DISTINCT e.aparelhoId) "
                                + "FROM EventoMidiaEntity e WHERE e.lojaId IN :lojas AND e.registradoEm >= :de "
                                + "GROUP BY e.codigoBarras, e.tipo", Object[].class)
                .setParameter("lojas", lojas)
                .setParameter("de", de)
                .getResultList();
        for (Object[] linha : distintos) {
            RelatorioOfertaDTO oferta = porCodigo.get((String) linha[0]);
            long aparelhos = (Long) linha[2];
            switch ((String) linha[1]) {
                case EventoMidiaEntity.EXIBICAO -> oferta.setAlcance(aparelhos);
                case EventoMidiaEntity.FAVORITAR -> oferta.setFavoritos(aparelhos);
                case EventoMidiaEntity.DESFAVORITAR -> oferta.setDesfavoritos(aparelhos);
                case EventoMidiaEntity.PRE_LISTA -> oferta.setPreLista(aparelhos);
                default -> {
                }
            }
        }

        long aparelhos = entityManager.createQuery(
                        "SELECT COUNT(DISTINCT e.aparelhoId) FROM EventoMidiaEntity e "
                                + "WHERE e.lojaId IN :lojas AND e.registradoEm >= :de", Long.class)
                .setParameter("lojas", lojas)
                .setParameter("de", de)
                .getSingleResult();

        List<EventoMidiaEntity> ultimos = entityManager.createQuery(
                        "SELECT e FROM EventoMidiaEntity e WHERE e.lojaId IN :lojas AND e.registradoEm >= :de "
                                + "ORDER BY e.registradoEm DESC, e.id DESC", EventoMidiaEntity.class)
                .setParameter("lojas", lojas)
                .setParameter("de", de)
                .setMaxResults(EVENTOS_RECENTES)
                .getResultList();

        Map<String, String> descricoes = descricoes(porCodigo.keySet());
        porCodigo.values().forEach(oferta -> oferta.setDescricao(descricoes.get(oferta.getCodigoBarras())));

        List<RelatorioOfertaDTO> ofertas = new ArrayList<>(porCodigo.values());
        ofertas.sort(Comparator.comparingLong(RelatorioOfertaDTO::getExibicoes).reversed()
                .thenComparing(RelatorioOfertaDTO::getCodigoBarras));

        return RelatorioMidiasDTO.builder()
                .periodo(periodo)
                .de(de.toString())
                .ate(ate.toString())
                .aparelhos(aparelhos)
                .totais(somar(ofertas))
                .ofertas(ofertas)
                .recentes(ultimos.stream().map(e -> EventoRecenteDTO.builder()
                        .registradoEm(e.getRegistradoEm().toString())
                        .tipo(e.getTipo())
                        .origem(e.getOrigem())
                        .codigoBarras(e.getCodigoBarras())
                        .descricao(descricoes.get(e.getCodigoBarras()))
                        .build()).toList())
                .instalacoes(instalacoes(lojas, de))
                .familia(comFamilia ? familiaService.relatorio(lojaPadraoId, de) : null)
                .build();
    }

    private RelatorioInstalacoesDTO instalacoes(List<Integer> lojas, OffsetDateTime de) {
        RelatorioInstalacoesDTO resumo = new RelatorioInstalacoesDTO();
        entityManager.createQuery(
                        "SELECT i.origem, i.plataforma, COUNT(i) FROM InstalacaoAppEntity i "
                                + "WHERE i.lojaId IN :lojas AND i.registradoEm >= :de GROUP BY i.origem, i.plataforma", Object[].class)
                .setParameter("lojas", lojas)
                .setParameter("de", de)
                .getResultList()
                .forEach(linha -> {
                    long quantidade = (Long) linha[2];
                    resumo.setTotal(resumo.getTotal() + quantidade);
                    if (InstalacaoAppEntity.BOTAO.equals(linha[0])) {
                        resumo.setBotao(resumo.getBotao() + quantidade);
                    } else {
                        resumo.setNavegador(resumo.getNavegador() + quantidade);
                    }
                    switch ((String) linha[1]) {
                        case InstalacaoAppEntity.ANDROID -> resumo.setAndroid(resumo.getAndroid() + quantidade);
                        case InstalacaoAppEntity.IOS -> resumo.setIos(resumo.getIos() + quantidade);
                        default -> resumo.setOutras(resumo.getOutras() + quantidade);
                    }
                });
        return resumo;
    }

    // Botão "Limpar dados" do painel (fase de testes): apaga todos os eventos e instalações da loja.
    @Transactional
    public int limpar(Integer lojaId) {
        int apagados = entityManager.createQuery("DELETE FROM EventoMidiaEntity e WHERE e.lojaId = :loja")
                .setParameter("loja", lojaId)
                .executeUpdate();
        int instalacoes = entityManager.createQuery("DELETE FROM InstalacaoAppEntity i WHERE i.lojaId = :loja")
                .setParameter("loja", lojaId)
                .executeUpdate();
        Log.infof("Relatório de mídias da loja %d limpo: %d eventos e %d instalações apagados.", lojaId, apagados, instalacoes);
        return apagados + instalacoes;
    }

    private RelatorioOfertaDTO linhaVazia(String codigoBarras) {
        return RelatorioOfertaDTO.builder().codigoBarras(codigoBarras).build();
    }

    private RelatorioOfertaDTO somar(List<RelatorioOfertaDTO> ofertas) {
        RelatorioOfertaDTO total = new RelatorioOfertaDTO();
        for (RelatorioOfertaDTO o : ofertas) {
            total.setExibicoes(total.getExibicoes() + o.getExibicoes());
            total.setExibicoesAnuncio(total.getExibicoesAnuncio() + o.getExibicoesAnuncio());
            total.setExibicoesTela(total.getExibicoesTela() + o.getExibicoesTela());
            total.setAlcance(total.getAlcance() + o.getAlcance());
            total.setFavoritos(total.getFavoritos() + o.getFavoritos());
            total.setDesfavoritos(total.getDesfavoritos() + o.getDesfavoritos());
            total.setLocalizar(total.getLocalizar() + o.getLocalizar());
            total.setLocalizarAnuncio(total.getLocalizarAnuncio() + o.getLocalizarAnuncio());
            total.setLocalizarTela(total.getLocalizarTela() + o.getLocalizarTela());
            total.setPreLista(total.getPreLista() + o.getPreLista());
            total.setPreListaAnuncio(total.getPreListaAnuncio() + o.getPreListaAnuncio());
            total.setPreListaTela(total.getPreListaTela() + o.getPreListaTela());
        }
        return total;
    }

    private Map<String, String> descricoes(Set<String> codigos) {
        Map<String, String> descricoes = new HashMap<>();
        if (codigos.isEmpty()) {
            return descricoes;
        }
        entityManager.createQuery(
                        "SELECT p.codigoBarras, COALESCE(p.descricaoExpandida, p.descricao) FROM ProdutoEntity p WHERE p.codigoBarras IN :codigos", Object[].class)
                .setParameter("codigos", codigos)
                .getResultList()
                .forEach(linha -> descricoes.put((String) linha[0], (String) linha[1]));
        return descricoes;
    }

    private UUID lerUuid(String texto) {
        try {
            return texto == null ? null : UUID.fromString(texto);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Loja que o app informou (multi-loja, regra 23d), se existir e estiver ativa; senão a padrão.
    private Integer lojaDoApp(Integer lojaId) {
        if (lojaId != null) {
            LojaEntity loja = entityManager.find(LojaEntity.class, lojaId);
            if (loja != null && loja.isAtiva()) {
                return lojaId;
            }
        }
        return lojaPadraoId;
    }
}
