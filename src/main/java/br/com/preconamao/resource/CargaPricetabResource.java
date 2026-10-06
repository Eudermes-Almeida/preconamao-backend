package br.com.preconamao.resource;

import br.com.preconamao.dto.CargaRecebidaDTO;
import br.com.preconamao.dto.SinalDTO;
import br.com.preconamao.dto.SinalRespostaDTO;
import br.com.preconamao.dto.SituacaoLojaDTO;
import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.service.CargaPricetabService;
import br.com.preconamao.service.LojaService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
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

import java.util.Optional;
import java.util.function.Function;

// Entrada do agente instalado na loja (modelagem_dados_postgres/agente_pricetab/). Toda chamada
// exige a chave da loja no cabeçalho X-Chave-Loja; sem ela, 401.
@Path("/cargas")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Carga Automática do PRICETAB", description = "Recebimento do PRICETAB e sinal de vida do agente da loja")
public class CargaPricetabResource {

    public static final String CABECALHO_CHAVE = "X-Chave-Loja";
    // Nome da cópia enviada pelo agente (PRICETAB_<loja>_<data-hora>.TXT): confere com a chave
    // (regra 7 do multi-loja). Opcional: o agente antigo não manda.
    public static final String CABECALHO_NOME = "X-Nome-Arquivo";

    @Inject
    CargaPricetabService cargaService;

    @Inject
    LojaService lojaService;

    @POST
    @Path("/pricetab")
    @Consumes(MediaType.WILDCARD)
    @APIResponses(value = {
            @APIResponse(responseCode = "202", description = "Arquivo recebido; processado em alguns segundos", content = @Content(schema = @Schema(implementation = CargaRecebidaDTO.class))),
            @APIResponse(responseCode = "200", description = "Arquivo igual ao já aplicado ou ao da última carga (nada novo)"),
            @APIResponse(responseCode = "400", description = "Arquivo vazio"),
            @APIResponse(responseCode = "401", description = "Chave da loja ausente ou inválida"),
            @APIResponse(responseCode = "413", description = "Arquivo maior que o limite"),
    })
    @Operation(summary = "Recebe o PRICETAB.TXT da loja", description = "Corpo = bytes do arquivo como estão (Latin-1). O processamento (diferença com o banco, localização, pré-lista) roda em seguida; acompanhe por GET /cargas/{id}.")
    public Response recebePricetab(@HeaderParam(CABECALHO_CHAVE) String chave,
                                   @HeaderParam(CABECALHO_NOME) String nomeArquivo, byte[] arquivo) {
        return comLoja(chave, loja -> {
            if (arquivo == null || arquivo.length == 0) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Arquivo vazio").build();
            }
            if (arquivo.length > CargaPricetabService.TAMANHO_MAXIMO_BYTES) {
                return Response.status(413).entity("Arquivo maior que o limite de "
                        + CargaPricetabService.TAMANHO_MAXIMO_BYTES / (1024 * 1024) + " MB").build();
            }
            CargaRecebidaDTO carga = cargaService.receber(loja, arquivo, nomeArquivo);
            return Response.status(carga.isNovaCarga() ? Response.Status.ACCEPTED : Response.Status.OK).entity(carga).build();
        });
    }

    @POST
    @Path("/sinal")
    @Consumes(MediaType.APPLICATION_JSON)
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Sinal registrado", content = @Content(schema = @Schema(implementation = SinalRespostaDTO.class))),
            @APIResponse(responseCode = "401", description = "Chave da loja ausente ou inválida"),
    })
    @Operation(summary = "Sinal de vida do agente", description = "Enviado a cada poucos minutos com o hash do PRICETAB da loja. Sem sinal além do limite, o app esconde os preços. enviarArquivo=true pede o reenvio do arquivo.")
    public Response registraSinal(@HeaderParam(CABECALHO_CHAVE) String chave, SinalDTO sinal) {
        return comLoja(chave, loja -> Response.ok(
                cargaService.registrarSinal(loja, sinal == null ? null : sinal.getHash())).build());
    }

    @GET
    @Path("/situacao")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Situação da loja", content = @Content(schema = @Schema(implementation = SituacaoLojaDTO.class))),
            @APIResponse(responseCode = "401", description = "Chave da loja ausente ou inválida"),
    })
    @Operation(summary = "Situação da loja", description = "Preço confiável, último sinal, alerta em andamento e as últimas cargas.")
    public Response situacao(@HeaderParam(CABECALHO_CHAVE) String chave) {
        return comLoja(chave, loja -> Response.ok(cargaService.situacaoLoja(loja.getId())).build());
    }

    @GET
    @Path("/{id}")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Situação da carga", content = @Content(schema = @Schema(implementation = CargaRecebidaDTO.class))),
            @APIResponse(responseCode = "401", description = "Chave da loja ausente ou inválida"),
            @APIResponse(responseCode = "404", description = "Carga não encontrada para esta loja"),
    })
    @Operation(summary = "Situação de uma carga")
    public Response carga(@HeaderParam(CABECALHO_CHAVE) String chave, @PathParam("id") Long id) {
        return comLoja(chave, loja -> {
            CargaRecebidaDTO carga = cargaService.buscarCarga(loja.getId(), id);
            return carga == null
                    ? Response.status(Response.Status.NOT_FOUND).entity("Carga não encontrada").build()
                    : Response.ok(carga).build();
        });
    }

    private Response comLoja(String chave, Function<LojaEntity, Response> acao) {
        try {
            Optional<LojaEntity> loja = lojaService.autenticar(chave);
            if (loja.isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED).entity("Chave da loja ausente ou inválida").build();
            }
            return acao.apply(loja.get());
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro na carga do PRICETAB: " + e.getMessage())
                    .build();
        }
    }
}
