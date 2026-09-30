package br.com.preconamao.resource;

import br.com.preconamao.dto.RelatorioMidiasDTO;
import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.service.EventoMidiaService;
import br.com.preconamao.service.LojaService;
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
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;
import java.util.Optional;

// Relatórios da aba administrativa (/admin do app). Exige a chave de relatório da loja no
// cabeçalho X-Chave-Relatorio (scripts/018); sem ela, 401.
@Path("/relatorios")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Relatórios", description = "Relatórios da aba administrativa")
public class RelatorioResource {

    public static final String CABECALHO_CHAVE = "X-Chave-Relatorio";

    @Inject
    EventoMidiaService eventoMidiaService;

    @Inject
    LojaService lojaService;

    @GET
    @Path("/midias")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Relatório do período", content = @Content(schema = @Schema(implementation = RelatorioMidiasDTO.class))),
            @APIResponse(responseCode = "400", description = "Período inválido"),
            @APIResponse(responseCode = "401", description = "Chave de relatório ausente ou inválida"),
    })
    @Operation(summary = "Relatório de mídias", description = "Exibições, alcance, favoritos, localizar e pré-lista por oferta. Período: hoje, 7dias ou 30dias (dias corridos em Brasília, incluindo hoje).")
    public Response midias(@HeaderParam(CABECALHO_CHAVE) String chave,
                           @QueryParam("periodo") @DefaultValue("7dias") String periodo) {
        try {
            Optional<LojaEntity> loja = lojaService.autenticarRelatorio(chave);
            if (loja.isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED).entity("Chave de relatório ausente ou inválida").build();
            }
            if (!EventoMidiaService.periodoValido(periodo)) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Período inválido: use hoje, 7dias ou 30dias").build();
            }
            return Response.ok(eventoMidiaService.relatorio(loja.get().getId(), periodo)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro no relatório de mídias: " + e.getMessage())
                    .build();
        }
    }

    // Fase de testes: zera o relatório da loja (todos os períodos). Não há como desfazer, a não ser
    // por backup do banco.
    @DELETE
    @Path("/midias")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Eventos e instalações apagados (quantidade no corpo)"),
            @APIResponse(responseCode = "401", description = "Chave de relatório ausente ou inválida"),
    })
    @Operation(summary = "Apaga todos os eventos de mídia e instalações do app da loja", description = "Usado pelo botão \"Limpar dados\" do painel administrativo durante os testes.")
    public Response limpaMidias(@HeaderParam(CABECALHO_CHAVE) String chave) {
        try {
            Optional<LojaEntity> loja = lojaService.autenticarRelatorio(chave);
            if (loja.isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED).entity("Chave de relatório ausente ou inválida").build();
            }
            return Response.ok(Map.of("apagados", eventoMidiaService.limpar(loja.get().getId()))).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro ao limpar o relatório de mídias: " + e.getMessage())
                    .build();
        }
    }
}
