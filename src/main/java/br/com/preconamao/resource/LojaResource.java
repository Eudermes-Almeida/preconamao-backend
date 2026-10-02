package br.com.preconamao.resource;

import br.com.preconamao.dto.LojaPublicaDTO;
import br.com.preconamao.dto.PosicaoLojaDTO;
import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.service.LojaService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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

// Lojas parceiras com localização (scripts/023). A lista é pública: o celular baixa e calcula a
// distância sozinho, sem enviar a posição do cliente ao servidor.
@Path("/lojas")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Lojas", description = "Lojas parceiras e a posição de cada uma")
public class LojaResource {

    @Inject
    LojaService lojaService;

    @GET
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Lojas com posição cadastrada", content = @Content(schema = @Schema(implementation = LojaPublicaDTO.class))),
    })
    @Operation(summary = "Lista as lojas parceiras", description = "Slug do QR code, nome curto, latitude, longitude e raio (metros) de cada loja com posição cadastrada.")
    public Response lista() {
        try {
            return Response.ok(lojaService.lojasComPosicao()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("mensagem", "Erro ao listar as lojas: " + e.getMessage()))
                    .build();
        }
    }

    // A loja é a da chave de relatório (a mesma do /admin): cada loja só grava a própria posição.
    @PUT
    @Path("/posicao")
    @Consumes(MediaType.APPLICATION_JSON)
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Posição gravada; devolve a loja como o app a vê"),
            @APIResponse(responseCode = "400", description = "Posição ou raio inválido"),
            @APIResponse(responseCode = "401", description = "Chave de relatório ausente ou inválida"),
    })
    @Operation(summary = "Registra a posição da loja", description = "Usado pelo botão \"Registrar a posição desta loja\" do /admin, de dentro da loja.")
    public Response registraPosicao(@HeaderParam(RelatorioResource.CABECALHO_CHAVE) String chave, PosicaoLojaDTO posicao) {
        try {
            Optional<LojaEntity> loja = lojaService.autenticarRelatorio(chave);
            if (loja.isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("mensagem", "Chave de relatório ausente ou inválida")).build();
            }
            String erro = lojaService.registrarPosicao(loja.get().getId(), posicao);
            if (erro != null) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("mensagem", erro)).build();
            }
            return Response.ok(lojaService.lojasComPosicao().stream()
                    .filter(l -> l.getId().equals(loja.get().getId())).findFirst().orElse(null)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("mensagem", "Erro ao registrar a posição: " + e.getMessage()))
                    .build();
        }
    }
}
