package br.com.preconamao.resource;

import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.service.EventoMidiaService;
import br.com.preconamao.service.RelatorioAcessoService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// Relatórios da aba administrativa (/admin do app), com a chave no cabeçalho X-Chave-Relatorio.
// Três chaves (regra 6 do multi-loja): da LOJA (só ela), da REDE (as lojas da rede, consolidado
// com filtro) e GERAL (quem opera o sistema: todas as lojas, filtro por rede e loja; só ela vê a
// Família). Sem chave válida, 401.
@Path("/relatorios")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Relatórios", description = "Relatórios da aba administrativa")
public class RelatorioResource {

    public static final String CABECALHO_CHAVE = "X-Chave-Relatorio";

    @Inject
    EventoMidiaService eventoMidiaService;

    @Inject
    RelatorioAcessoService acessoService;

    @GET
    @Path("/lojas")
    @Operation(summary = "Lojas que a chave enxerga", description = "Para o filtro do painel: id, nome, rede e o tipo de acesso da chave (LOJA, REDE ou GERAL).")
    public Response lojas(@HeaderParam(CABECALHO_CHAVE) String chave) {
        Optional<RelatorioAcessoService.Acesso> acesso = acessoService.autenticar(chave);
        if (acesso.isEmpty()) {
            return naoAutorizado();
        }
        return Response.ok(Map.of(
                "acesso", acesso.get().tipo(),
                "lojas", acesso.get().lojas().stream().map(l -> Map.of(
                        "id", l.getId(),
                        "nome", l.getNomeCurto() != null ? l.getNomeCurto() : l.getNome(),
                        "redeId", l.getRedeId() == null ? 0 : l.getRedeId())).toList()))
                .build();
    }

    @GET
    @Path("/midias")
    @Operation(summary = "Relatório de mídias", description = "Exibições, favoritos, localizar e pré-lista por oferta; instalações; Família (só chave geral). ?loja= filtra uma loja; sem ele, consolida todas as que a chave enxerga.")
    public Response midias(@HeaderParam(CABECALHO_CHAVE) String chave,
                           @QueryParam("periodo") @DefaultValue("7dias") String periodo,
                           @QueryParam("loja") Integer lojaId) {
        try {
            Optional<RelatorioAcessoService.Acesso> acesso = acessoService.autenticar(chave);
            if (acesso.isEmpty()) {
                return naoAutorizado();
            }
            if (!EventoMidiaService.periodoValido(periodo)) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Período inválido: use hoje, 7dias ou 30dias").build();
            }
            List<Integer> lojas = acesso.get().lojas().stream().map(LojaEntity::getId)
                    .filter(id -> lojaId == null || id.equals(lojaId)).toList();
            if (lojas.isEmpty()) {
                return Response.status(Response.Status.FORBIDDEN).entity("Esta chave não dá acesso a essa loja").build();
            }
            return Response.ok(eventoMidiaService.relatorio(lojas, periodo,
                    RelatorioAcessoService.GERAL.equals(acesso.get().tipo()))).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro no relatório de mídias: " + e.getMessage())
                    .build();
        }
    }

    // Fase de testes: zera o relatório de UMA loja (todos os períodos) — com a chave de rede ou
    // geral, a loja vem em ?loja=. Não há como desfazer, a não ser por backup do banco.
    @DELETE
    @Path("/midias")
    @Operation(summary = "Limpa o relatório de uma loja")
    public Response limpaMidias(@HeaderParam(CABECALHO_CHAVE) String chave, @QueryParam("loja") Integer lojaId) {
        try {
            Optional<RelatorioAcessoService.Acesso> acesso = acessoService.autenticar(chave);
            if (acesso.isEmpty()) {
                return naoAutorizado();
            }
            Integer alvo = RelatorioAcessoService.LOJA.equals(acesso.get().tipo())
                    ? acesso.get().lojas().get(0).getId() : lojaId;
            if (alvo == null || acesso.get().lojas().stream().noneMatch(l -> l.getId().equals(alvo))) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Escolha uma loja para limpar").build();
            }
            return Response.ok(Map.of("apagados", eventoMidiaService.limpar(alvo))).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro ao limpar o relatório de mídias: " + e.getMessage())
                    .build();
        }
    }

    private static Response naoAutorizado() {
        return Response.status(Response.Status.UNAUTHORIZED).entity("Chave de relatório ausente ou inválida").build();
    }
}
