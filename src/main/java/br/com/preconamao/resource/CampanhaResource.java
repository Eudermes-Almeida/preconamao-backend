package br.com.preconamao.resource;

import br.com.preconamao.service.CampanhaService;
import br.com.preconamao.service.LojaService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

// Vitrine de ofertas do app: campanhas de mídia válidas na loja, cada uma com o produto e o preço
// desta loja (regra 4a do multi-loja). Substitui a lista de artes que ficava no código do app.
@Path("/campanhas")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Campanhas", description = "Ofertas (campanhas de mídia) da loja")
public class CampanhaResource {

    @Inject
    CampanhaService campanhaService;

    @Inject
    LojaService lojaService;

    @GET
    @Operation(summary = "Campanhas da loja", description = "Ativas, dentro da validade, com alcance que inclui a loja e com o produto ativo e com preço nela.")
    public Response campanhas(@QueryParam("loja") Integer lojaId) {
        try {
            return Response.ok(campanhaService.campanhas(lojaService.lojaDaConsulta(lojaId))).build();
        } catch (LojaService.LojaIndisponivel e) {
            return RespostaLoja.indisponivel(e);
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Erro ao carregar as campanhas: " + e.getMessage()).build();
        }
    }
}
