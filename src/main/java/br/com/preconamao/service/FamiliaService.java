package br.com.preconamao.service;

import br.com.preconamao.dto.ConteudoListaDTO;
import br.com.preconamao.dto.ConviteDTO;
import br.com.preconamao.dto.FamiliaContatoDTO;
import br.com.preconamao.dto.FamiliaEstadoDTO;
import br.com.preconamao.dto.ListaRecebidaDTO;
import br.com.preconamao.dto.ProdutoListaDTO;
import br.com.preconamao.dto.RelatorioFamiliaDTO;
import br.com.preconamao.entity.FamiliaContatoEntity;
import br.com.preconamao.entity.FamiliaConviteEntity;
import br.com.preconamao.entity.FamiliaListaEntity;
import br.com.preconamao.entity.FamiliaMembroEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

// "Família": ligar aparelhos por convite e mandar itens da pré-lista de um para outro. Ver
// scripts/020_familia.sql. Cada aparelho se identifica pela chave secreta que ele mesmo gerou
// (cabeçalho X-Chave-Familia); aqui só se guarda o hash.
@ApplicationScoped
public class FamiliaService {

    public static final int DIAS_VALIDADE_CONVITE = 7;

    // Contra abuso (a API é aberta, sem login): por aparelho, a cada 24 h.
    private static final int MAX_CONVITES_POR_DIA = 20;
    private static final int MAX_LISTAS_POR_DIA = 100;

    private static final int MAX_LINHAS_LISTA = 200;
    private static final int MAX_QUANTIDADE = 99;
    private static final int MAX_NOME = 30;

    // Sem 0/O, 1/I/L: o código também é digitado à mão.
    private static final String ALFABETO_CODIGO = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int TAMANHO_CODIGO = 8;

    private static final Pattern CHAVE = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final Pattern CODIGO_BARRAS = Pattern.compile("\\d{8,14}");
    private static final Pattern ID_ITEM = Pattern.compile("\\d{1,9}");

    private final SecureRandom aleatorio = new SecureRandom();
    private final Jsonb jsonb = JsonbBuilder.create();

    @ConfigProperty(name = "loja.id-padrao", defaultValue = "1")
    Integer lojaPadraoId;

    @Inject
    EntityManager entityManager;

    // Erro de regra, com o status HTTP que o resource devolve e a mensagem que o app mostra.
    public static class FamiliaException extends RuntimeException {
        private final int status;

        public FamiliaException(int status, String mensagem) {
            super(mensagem);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Membro
    // ------------------------------------------------------------------------------------------

    public static boolean chaveValida(String chave) {
        return chave != null && CHAVE.matcher(chave).matches();
    }

    private Optional<FamiliaMembroEntity> buscarMembro(String chave) {
        return entityManager.createQuery("SELECT m FROM FamiliaMembroEntity m WHERE m.chaveHash = :hash", FamiliaMembroEntity.class)
                .setParameter("hash", LojaService.sha256(chave.getBytes(StandardCharsets.UTF_8)))
                .getResultStream()
                .findFirst();
    }

    // O membro nasce na primeira ação dele na Família (nome, convite ou aceite).
    private FamiliaMembroEntity buscarOuCriarMembro(String chave) {
        return buscarMembro(chave).orElseGet(() -> {
            FamiliaMembroEntity novo = FamiliaMembroEntity.builder()
                    .chaveHash(LojaService.sha256(chave.getBytes(StandardCharsets.UTF_8)))
                    .criadoEm(OffsetDateTime.now())
                    .build();
            entityManager.persist(novo);
            return novo;
        });
    }

    private FamiliaMembroEntity membroComNome(String chave) {
        FamiliaMembroEntity eu = buscarOuCriarMembro(chave);
        if (eu.getNome() == null) {
            throw new FamiliaException(400, "Informe o seu nome antes");
        }
        return eu;
    }

    @Transactional
    public String definirNome(String chave, String nome) {
        FamiliaMembroEntity eu = buscarOuCriarMembro(chave);
        eu.setNome(limparTexto(nome, "nome"));
        return eu.getNome();
    }

    // ------------------------------------------------------------------------------------------
    // Estado (consultado pelo app a cada ~30 s)
    // ------------------------------------------------------------------------------------------

    @Transactional
    public FamiliaEstadoDTO estado(String chave) {
        Optional<FamiliaMembroEntity> eu = buscarMembro(chave);
        if (eu.isEmpty()) {
            return FamiliaEstadoDTO.builder().contatos(List.of()).recebidas(List.of()).build();
        }
        Long meuId = eu.get().getId();

        List<Object[]> linhas = entityManager.createQuery(
                        "SELECT c.contatoId, c.apelido, m.nome FROM FamiliaContatoEntity c, FamiliaMembroEntity m "
                                + "WHERE c.membroId = :eu AND m.id = c.contatoId ORDER BY c.criadoEm", Object[].class)
                .setParameter("eu", meuId)
                .getResultList();
        Map<Long, FamiliaContatoDTO> contatos = new LinkedHashMap<>();
        for (Object[] linha : linhas) {
            contatos.put((Long) linha[0], FamiliaContatoDTO.builder()
                    .id((Long) linha[0]).apelido((String) linha[1]).nome((String) linha[2]).build());
        }

        List<FamiliaListaEntity> pendentes = entityManager.createQuery(
                        "SELECT l FROM FamiliaListaEntity l WHERE l.paraMembroId = :eu AND l.situacao = :pendente ORDER BY l.enviadaEm, l.id",
                        FamiliaListaEntity.class)
                .setParameter("eu", meuId)
                .setParameter("pendente", FamiliaListaEntity.PENDENTE)
                .getResultList();
        Map<Long, String> nomes = new HashMap<>();
        List<ListaRecebidaDTO> recebidas = pendentes.stream().map(lista -> {
            FamiliaContatoDTO contato = contatos.get(lista.getDeMembroId());
            // Ligação removida depois do envio: a lista ainda chega, com o nome de quem mandou.
            String nome = contato != null ? contato.getNome()
                    : nomes.computeIfAbsent(lista.getDeMembroId(), id -> entityManager.find(FamiliaMembroEntity.class, id).getNome());
            return ListaRecebidaDTO.builder()
                    .id(lista.getId())
                    .contatoId(lista.getDeMembroId())
                    .apelido(contato != null ? contato.getApelido() : nome)
                    .nome(nome)
                    .conteudo(jsonb.fromJson(lista.getConteudo(), ConteudoListaDTO.class))
                    .quantidadeItens(lista.getQuantidadeItens())
                    .enviadaEm(lista.getEnviadaEm().toString())
                    .build();
        }).toList();

        return FamiliaEstadoDTO.builder()
                .nome(eu.get().getNome())
                .contatos(List.copyOf(contatos.values()))
                .recebidas(recebidas)
                .build();
    }

    // ------------------------------------------------------------------------------------------
    // Convites
    // ------------------------------------------------------------------------------------------

    @Transactional
    public ConviteDTO criarConvite(String chave, String apelido) {
        FamiliaMembroEntity eu = membroComNome(chave);
        String apelidoLimpo = limparTexto(apelido, "apelido");
        OffsetDateTime agora = OffsetDateTime.now();

        long recentes = entityManager.createQuery(
                        "SELECT COUNT(c) FROM FamiliaConviteEntity c WHERE c.deMembroId = :eu AND c.criadoEm >= :desde", Long.class)
                .setParameter("eu", eu.getId())
                .setParameter("desde", agora.minusDays(1))
                .getSingleResult();
        if (recentes >= MAX_CONVITES_POR_DIA) {
            throw new FamiliaException(429, "Muitos convites hoje. Tente amanhã.");
        }

        FamiliaConviteEntity convite = FamiliaConviteEntity.builder()
                .codigo(novoCodigo())
                .lojaId(lojaPadraoId)
                .deMembroId(eu.getId())
                .apelidoConvidado(apelidoLimpo)
                .criadoEm(agora)
                .expiraEm(agora.plusDays(DIAS_VALIDADE_CONVITE))
                .build();
        entityManager.persist(convite);
        return ConviteDTO.builder()
                .codigo(convite.getCodigo())
                .deNome(eu.getNome())
                .situacao("VALIDO")
                .expiraEm(convite.getExpiraEm().toString())
                .build();
    }

    @Transactional
    public ConviteDTO consultarConvite(String chave, String codigo) {
        FamiliaConviteEntity convite = buscarConvite(codigo);
        Optional<FamiliaMembroEntity> eu = buscarMembro(chave);
        return ConviteDTO.builder()
                .codigo(convite.getCodigo())
                .deNome(entityManager.find(FamiliaMembroEntity.class, convite.getDeMembroId()).getNome())
                .situacao(situacao(convite, eu.map(FamiliaMembroEntity::getId).orElse(null)))
                .expiraEm(convite.getExpiraEm().toString())
                .build();
    }

    // Aceito, a ligação vale nos dois sentidos: cada lado com o apelido que deu ao outro. Aceitar
    // de novo quem já está ligado só atualiza os apelidos.
    @Transactional
    public FamiliaContatoDTO aceitarConvite(String chave, String codigo, String apelido) {
        FamiliaMembroEntity eu = membroComNome(chave);
        String apelidoLimpo = limparTexto(apelido, "apelido");
        FamiliaConviteEntity convite = buscarConvite(codigo);
        switch (situacao(convite, eu.getId())) {
            case "USADO" -> throw new FamiliaException(410, "Este convite já foi usado. Peça um novo.");
            case "VENCIDO" -> throw new FamiliaException(410, "Este convite venceu. Peça um novo.");
            case "PROPRIO" -> throw new FamiliaException(409, "Este convite é seu. Envie o link para a outra pessoa.");
            default -> {
                // VALIDO
            }
        }
        OffsetDateTime agora = OffsetDateTime.now();
        ligar(eu.getId(), convite.getDeMembroId(), apelidoLimpo, agora);
        ligar(convite.getDeMembroId(), eu.getId(), convite.getApelidoConvidado(), agora);
        convite.setAceitoEm(agora);
        convite.setAceitoPorId(eu.getId());

        FamiliaMembroEntity deQuem = entityManager.find(FamiliaMembroEntity.class, convite.getDeMembroId());
        return FamiliaContatoDTO.builder().id(deQuem.getId()).apelido(apelidoLimpo).nome(deQuem.getNome()).build();
    }

    private void ligar(Long membroId, Long contatoId, String apelido, OffsetDateTime agora) {
        Optional<FamiliaContatoEntity> existente = buscarContato(membroId, contatoId);
        if (existente.isPresent()) {
            existente.get().setApelido(apelido);
        } else {
            entityManager.persist(FamiliaContatoEntity.builder()
                    .membroId(membroId).contatoId(contatoId).apelido(apelido).criadoEm(agora).build());
        }
    }

    // Remover desfaz a ligação dos dois lados: nenhum dos dois consegue mais enviar ao outro.
    @Transactional
    public void removerContato(String chave, Long contatoId) {
        FamiliaMembroEntity eu = buscarMembro(chave)
                .orElseThrow(() -> new FamiliaException(404, "Contato não encontrado"));
        int apagados = entityManager.createQuery(
                        "DELETE FROM FamiliaContatoEntity c WHERE (c.membroId = :eu AND c.contatoId = :outro) "
                                + "OR (c.membroId = :outro AND c.contatoId = :eu)")
                .setParameter("eu", eu.getId())
                .setParameter("outro", contatoId)
                .executeUpdate();
        if (apagados == 0) {
            throw new FamiliaException(404, "Contato não encontrado");
        }
    }

    private FamiliaConviteEntity buscarConvite(String codigo) {
        String normalizado = codigo == null ? "" : codigo.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return entityManager.createQuery("SELECT c FROM FamiliaConviteEntity c WHERE c.codigo = :codigo", FamiliaConviteEntity.class)
                .setParameter("codigo", normalizado)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new FamiliaException(404, "Convite não encontrado. Confira o código."));
    }

    private String situacao(FamiliaConviteEntity convite, Long meuId) {
        if (convite.getDeMembroId().equals(meuId)) {
            return "PROPRIO";
        }
        if (convite.getAceitoEm() != null) {
            return "USADO";
        }
        return convite.getExpiraEm().isBefore(OffsetDateTime.now()) ? "VENCIDO" : "VALIDO";
    }

    private String novoCodigo() {
        while (true) {
            StringBuilder codigo = new StringBuilder(TAMANHO_CODIGO);
            for (int i = 0; i < TAMANHO_CODIGO; i++) {
                codigo.append(ALFABETO_CODIGO.charAt(aleatorio.nextInt(ALFABETO_CODIGO.length())));
            }
            long existentes = entityManager.createQuery("SELECT COUNT(c) FROM FamiliaConviteEntity c WHERE c.codigo = :codigo", Long.class)
                    .setParameter("codigo", codigo.toString())
                    .getSingleResult();
            if (existentes == 0) {
                return codigo.toString();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Listas
    // ------------------------------------------------------------------------------------------

    @Transactional
    public Long enviarLista(String chave, Long paraId, ConteudoListaDTO conteudo) {
        FamiliaMembroEntity eu = membroComNome(chave);
        if (paraId == null || buscarContato(eu.getId(), paraId).isEmpty()) {
            throw new FamiliaException(404, "Esta pessoa não está mais ligada a você.");
        }
        ConteudoListaDTO limpo = validarConteudo(conteudo);
        int linhas = limpo.getItens().size() + limpo.getProdutos().size();

        OffsetDateTime agora = OffsetDateTime.now();
        long recentes = entityManager.createQuery(
                        "SELECT COUNT(l) FROM FamiliaListaEntity l WHERE l.deMembroId = :eu AND l.enviadaEm >= :desde", Long.class)
                .setParameter("eu", eu.getId())
                .setParameter("desde", agora.minusDays(1))
                .getSingleResult();
        if (recentes >= MAX_LISTAS_POR_DIA) {
            throw new FamiliaException(429, "Muitas listas enviadas hoje. Tente amanhã.");
        }

        FamiliaListaEntity lista = FamiliaListaEntity.builder()
                .lojaId(lojaPadraoId)
                .deMembroId(eu.getId())
                .paraMembroId(paraId)
                .conteudo(jsonb.toJson(limpo))
                .quantidadeItens(linhas)
                .situacao(FamiliaListaEntity.PENDENTE)
                .enviadaEm(agora)
                .build();
        entityManager.persist(lista);
        return lista.getId();
    }

    // "Juntar" (aceita = true) ou "Recusar". A soma na pré-lista é feita pelo app; aqui só a
    // situação, para a lista não aparecer de novo.
    @Transactional
    public void resolverLista(String chave, Long listaId, boolean aceita) {
        FamiliaMembroEntity eu = buscarMembro(chave)
                .orElseThrow(() -> new FamiliaException(404, "Lista não encontrada"));
        FamiliaListaEntity lista = entityManager.find(FamiliaListaEntity.class, listaId);
        if (lista == null || !lista.getParaMembroId().equals(eu.getId())) {
            throw new FamiliaException(404, "Lista não encontrada");
        }
        if (!FamiliaListaEntity.PENDENTE.equals(lista.getSituacao())) {
            throw new FamiliaException(409, "Esta lista já foi respondida");
        }
        lista.setSituacao(aceita ? FamiliaListaEntity.ACEITA : FamiliaListaEntity.RECUSADA);
        lista.setResolvidaEm(OffsetDateTime.now());
    }

    private ConteudoListaDTO validarConteudo(ConteudoListaDTO conteudo) {
        Map<String, Integer> itens = new LinkedHashMap<>();
        Map<String, ProdutoListaDTO> produtos = new LinkedHashMap<>();
        if (conteudo != null && conteudo.getItens() != null) {
            conteudo.getItens().forEach((id, quantidade) -> {
                if (ID_ITEM.matcher(id).matches() && quantidadeValida(quantidade)) {
                    itens.put(id, quantidade);
                }
            });
        }
        if (conteudo != null && conteudo.getProdutos() != null) {
            conteudo.getProdutos().forEach((codigo, produto) -> {
                if (CODIGO_BARRAS.matcher(codigo).matches() && produto != null && quantidadeValida(produto.getQuantidade())
                        && produto.getDescricao() != null && !produto.getDescricao().isBlank()) {
                    String descricao = produto.getDescricao().strip();
                    produtos.put(codigo, new ProdutoListaDTO(descricao.substring(0, Math.min(60, descricao.length())), produto.getQuantidade()));
                }
            });
        }
        int linhas = itens.size() + produtos.size();
        if (linhas == 0) {
            throw new FamiliaException(400, "A lista está vazia");
        }
        if (linhas > MAX_LINHAS_LISTA) {
            throw new FamiliaException(400, "Lista grande demais");
        }
        return new ConteudoListaDTO(itens, produtos);
    }

    private boolean quantidadeValida(Integer quantidade) {
        return quantidade != null && quantidade >= 1 && quantidade <= MAX_QUANTIDADE;
    }

    private Optional<FamiliaContatoEntity> buscarContato(Long membroId, Long contatoId) {
        return entityManager.createQuery(
                        "SELECT c FROM FamiliaContatoEntity c WHERE c.membroId = :membro AND c.contatoId = :contato", FamiliaContatoEntity.class)
                .setParameter("membro", membroId)
                .setParameter("contato", contatoId)
                .getResultStream()
                .findFirst();
    }

    // ------------------------------------------------------------------------------------------
    // Relatório (/admin)
    // ------------------------------------------------------------------------------------------

    @Transactional
    public RelatorioFamiliaDTO relatorio(Integer lojaId, OffsetDateTime de) {
        long ligacoes = entityManager.createQuery(
                        "SELECT COUNT(c) FROM FamiliaConviteEntity c WHERE c.lojaId = :loja AND c.aceitoEm >= :de", Long.class)
                .setParameter("loja", lojaId)
                .setParameter("de", de)
                .getSingleResult();
        RelatorioFamiliaDTO relatorio = RelatorioFamiliaDTO.builder().ligacoes(ligacoes).build();
        entityManager.createQuery(
                        "SELECT l.situacao, COUNT(l), SUM(l.quantidadeItens) FROM FamiliaListaEntity l "
                                + "WHERE l.lojaId = :loja AND l.enviadaEm >= :de GROUP BY l.situacao", Object[].class)
                .setParameter("loja", lojaId)
                .setParameter("de", de)
                .getResultList()
                .forEach(linha -> {
                    long listas = (Long) linha[1];
                    relatorio.setListasEnviadas(relatorio.getListasEnviadas() + listas);
                    relatorio.setItensEnviados(relatorio.getItensEnviados() + ((Number) linha[2]).longValue());
                    if (FamiliaListaEntity.ACEITA.equals(linha[0])) {
                        relatorio.setListasAceitas(listas);
                    } else if (FamiliaListaEntity.RECUSADA.equals(linha[0])) {
                        relatorio.setListasRecusadas(listas);
                    }
                });
        return relatorio;
    }

    // ------------------------------------------------------------------------------------------

    private String limparTexto(String texto, String campo) {
        String limpo = texto == null ? "" : texto.strip().replaceAll("\\s+", " ");
        if (limpo.isEmpty()) {
            throw new FamiliaException(400, "Informe o " + campo);
        }
        return limpo.substring(0, Math.min(MAX_NOME, limpo.length()));
    }
}
