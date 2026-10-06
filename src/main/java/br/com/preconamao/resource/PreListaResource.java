package br.com.preconamao.resource;

import br.com.preconamao.dto.PreListaCategoriaDTO;
import br.com.preconamao.service.LojaService;
import br.com.preconamao.service.PreListaService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
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

@Path("/pre-lista")
public class PreListaResource {

    @Inject
    PreListaService preListaService;

    @Inject
    LojaService lojaService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Pré-lista de Compras", description = "Catálogo de intenções de compra agrupadas por categoria")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Categorias com seus itens", content = @Content(schema = @Schema(implementation = PreListaCategoriaDTO.class))),
            @APIResponse(responseCode = "500", description = "Erro interno do servidor"),
    })
    @Operation(summary = "Lista o catálogo da pré-lista", description = "Retorna as categorias (accordions) e os itens genéricos que o cliente pode marcar na pré-lista de compras.")
    public Response listaCatalogo(@QueryParam("loja") Integer lojaId) {
        try {
            return Response.ok(preListaService.listaCatalogo(lojaService.lojaDaConsulta(lojaId))).build();
        } catch (LojaService.LojaIndisponivel e) {
            return RespostaLoja.indisponivel(e);
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro ao carregar a pré-lista: " + e.getMessage())
                    .build();
        }
    }

}
