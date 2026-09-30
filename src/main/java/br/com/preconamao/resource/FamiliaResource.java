package br.com.preconamao.resource;

import br.com.preconamao.dto.AceitarConviteDTO;
import br.com.preconamao.dto.ConviteDTO;
import br.com.preconamao.dto.EnviarListaDTO;
import br.com.preconamao.dto.FamiliaContatoDTO;
import br.com.preconamao.dto.FamiliaEstadoDTO;
import br.com.preconamao.dto.FamiliaNomeDTO;
import br.com.preconamao.dto.NovoConviteDTO;
import br.com.preconamao.service.FamiliaService;
import br.com.preconamao.service.FamiliaService.FamiliaException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
import java.util.function.Supplier;

// "Família": mandar itens da pré-lista de um celular para outro, sem cadastro. Todo endpoint exige
// a chave secreta do aparelho no cabeçalho X-Chave-Familia (gerada pelo próprio app); erros de
// regra voltam como {"mensagem": "..."}, que o app mostra ao cliente.
@Path("/familia")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Família", description = "Ligação entre aparelhos e envio de itens da pré-lista")
public class FamiliaResource {

    public static final String CABECALHO_CHAVE = "X-Chave-Familia";

    @Inject
    FamiliaService familiaService;

    @GET
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Nome, contatos e listas pendentes", content = @Content(schema = @Schema(implementation = FamiliaEstadoDTO.class))),
            @APIResponse(responseCode = "401", description = "Chave do aparelho ausente ou inválida"),
    })
    @Operation(summary = "Estado da Família deste aparelho", description = "Consultado pelo app a cada ~30 s com a pré-lista em uso.")
    public Response estado(@HeaderParam(CABECALHO_CHAVE) String chave) {
        return executar(chave, () -> Response.ok(familiaService.estado(chave)).build());
    }

    @PUT
    @Path("/eu")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Define o nome deste aparelho", description = "O primeiro nome que aparece para os contatos (\"Maria enviou 6 itens\").")
    public Response definirNome(@HeaderParam(CABECALHO_CHAVE) String chave, FamiliaNomeDTO corpo) {
        return executar(chave, () -> Response.ok(new FamiliaNomeDTO(
                familiaService.definirNome(chave, corpo == null ? null : corpo.getNome()))).build());
    }

    @POST
    @Path("/convites")
    @Consumes(MediaType.APPLICATION_JSON)
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Convite criado", content = @Content(schema = @Schema(implementation = ConviteDTO.class))),
            @APIResponse(responseCode = "400", description = "Sem nome ou sem apelido"),
            @APIResponse(responseCode = "429", description = "Convites demais nas últimas 24 h"),
    })
    @Operation(summary = "Cria um convite", description = "Código de 8 caracteres, uso único, válido por 7 dias.")
    public Response criarConvite(@HeaderParam(CABECALHO_CHAVE) String chave, NovoConviteDTO corpo) {
        return executar(chave, () -> Response.ok(familiaService.criarConvite(chave, corpo == null ? null : corpo.getApelido())).build());
    }

    @GET
    @Path("/convites/{codigo}")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Quem convidou e a situação (VALIDO, USADO, VENCIDO, PROPRIO)", content = @Content(schema = @Schema(implementation = ConviteDTO.class))),
            @APIResponse(responseCode = "404", description = "Código inexistente"),
    })
    @Operation(summary = "Consulta um convite", description = "Usado ao abrir o link, para mostrar \"Maria convidou você\".")
    public Response consultarConvite(@HeaderParam(CABECALHO_CHAVE) String chave, @PathParam("codigo") String codigo) {
        return executar(chave, () -> Response.ok(familiaService.consultarConvite(chave, codigo)).build());
    }

    @POST
    @Path("/convites/{codigo}/aceitar")
    @Consumes(MediaType.APPLICATION_JSON)
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Ligados nos dois sentidos", content = @Content(schema = @Schema(implementation = FamiliaContatoDTO.class))),
            @APIResponse(responseCode = "404", description = "Código inexistente"),
            @APIResponse(responseCode = "409", description = "Convite do próprio aparelho"),
            @APIResponse(responseCode = "410", description = "Convite já usado ou vencido"),
    })
    @Operation(summary = "Aceita um convite", description = "Liga os dois aparelhos; os dois passam a poder enviar listas um ao outro.")
    public Response aceitarConvite(@HeaderParam(CABECALHO_CHAVE) String chave, @PathParam("codigo") String codigo, AceitarConviteDTO corpo) {
        return executar(chave, () -> Response.ok(familiaService.aceitarConvite(chave, codigo, corpo == null ? null : corpo.getApelido())).build());
    }

    @DELETE
    @Path("/contatos/{id}")
    @Operation(summary = "Desfaz a ligação com um contato", description = "Vale para os dois lados.")
    public Response removerContato(@HeaderParam(CABECALHO_CHAVE) String chave, @PathParam("id") Long id) {
        return executar(chave, () -> {
            familiaService.removerContato(chave, id);
            return Response.noContent().build();
        });
    }

    @POST
    @Path("/listas")
    @Consumes(MediaType.APPLICATION_JSON)
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Lista enviada (id no corpo)"),
            @APIResponse(responseCode = "400", description = "Lista vazia, grande demais ou sem nome"),
            @APIResponse(responseCode = "404", description = "Destinatário não está ligado a este aparelho"),
            @APIResponse(responseCode = "429", description = "Listas demais nas últimas 24 h"),
    })
    @Operation(summary = "Envia itens da pré-lista para um contato")
    public Response enviarLista(@HeaderParam(CABECALHO_CHAVE) String chave, EnviarListaDTO corpo) {
        return executar(chave, () -> Response.ok(Map.of("id",
                familiaService.enviarLista(chave, corpo == null ? null : corpo.getParaId(), corpo == null ? null : corpo.getConteudo()))).build());
    }

    @POST
    @Path("/listas/{id}/aceitar")
    @Operation(summary = "Marca uma lista recebida como aceita (\"Juntar\")", description = "A soma na pré-lista é feita pelo app.")
    public Response aceitarLista(@HeaderParam(CABECALHO_CHAVE) String chave, @PathParam("id") Long id) {
        return executar(chave, () -> {
            familiaService.resolverLista(chave, id, true);
            return Response.noContent().build();
        });
    }

    @POST
    @Path("/listas/{id}/recusar")
    @Operation(summary = "Recusa uma lista recebida")
    public Response recusarLista(@HeaderParam(CABECALHO_CHAVE) String chave, @PathParam("id") Long id) {
        return executar(chave, () -> {
            familiaService.resolverLista(chave, id, false);
            return Response.noContent().build();
        });
    }

    private Response executar(String chave, Supplier<Response> acao) {
        if (!FamiliaService.chaveValida(chave)) {
            return erro(401, "Chave do aparelho ausente ou inválida");
        }
        try {
            return acao.get();
        } catch (FamiliaException e) {
            return erro(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            return erro(500, "Erro na Família: " + e.getMessage());
        }
    }

    private Response erro(int status, String mensagem) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("mensagem", mensagem)).build();
    }
}
