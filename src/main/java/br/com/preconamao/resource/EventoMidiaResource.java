package br.com.preconamao.resource;

import br.com.preconamao.dto.InstalacaoAppDTO;
import br.com.preconamao.dto.LoteEventosDTO;
import br.com.preconamao.service.EventoMidiaService;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

// Entrada dos eventos das ofertas enviados pelo app do cliente (sem login). Aberto de propósito;
// a proteção contra abuso fica no EventoMidiaService (limite por aparelho e validação de cada evento).
@Path("/eventos")
@Tag(name = "Eventos de Mídia", description = "Exibições e interações com as ofertas, para o relatório de mídias")
public class EventoMidiaResource {

    @Inject
    EventoMidiaService eventoMidiaService;

    private final Jsonb jsonb = JsonbBuilder.create();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @APIResponses(value = {
            @APIResponse(responseCode = "204", description = "Lote recebido (eventos inválidos ou acima do limite são descartados)"),
            @APIResponse(responseCode = "400", description = "Lote vazio, sem aparelho ou com mais eventos que o permitido"),
    })
    @Operation(summary = "Recebe um lote de eventos das ofertas", description = "Tipos: EXIBICAO, FAVORITAR, DESFAVORITAR, LOCALIZAR, PRE_LISTA. Origens: ANUNCIO, TELA_OFERTAS.")
    public Response recebe(LoteEventosDTO lote) {
        if (lote == null || lote.getEventos() == null || lote.getEventos().isEmpty()
                || lote.getEventos().size() > EventoMidiaService.MAX_EVENTOS_POR_LOTE) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Lote vazio ou com mais de " + EventoMidiaService.MAX_EVENTOS_POR_LOTE + " eventos").build();
        }
        try {
            eventoMidiaService.registrar(lote);
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro ao registrar eventos: " + e.getMessage())
                    .build();
        }
    }

    @POST
    @Path("/instalacao")
    @Consumes(MediaType.APPLICATION_JSON)
    @APIResponses(value = {
            @APIResponse(responseCode = "204", description = "Instalação registrada (ou já registrada antes para o aparelho)"),
            @APIResponse(responseCode = "400", description = "Aparelho, origem ou plataforma inválidos"),
    })
    @Operation(summary = "Registra o app instalado na tela inicial", description = "Uma vez por aparelho. Origens: BOTAO, NAVEGADOR. Plataformas: ANDROID, IOS, OUTRA.")
    public Response recebeInstalacao(InstalacaoAppDTO instalacao) {
        if (instalacao == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Corpo vazio").build();
        }
        try {
            if (!eventoMidiaService.registrarInstalacao(instalacao)) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Aparelho, origem ou plataforma inválidos").build();
            }
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro ao registrar instalação: " + e.getMessage())
                    .build();
        }
    }

    // Mesmo lote enviado por navigator.sendBeacon ao fechar/sair do app: o beacon só manda
    // text/plain sem preflight de CORS, então o JSON chega como texto.
    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    @Operation(summary = "Recebe um lote de eventos (texto)", description = "O mesmo JSON de POST /eventos, enviado como text/plain por navigator.sendBeacon.")
    public Response recebeTexto(String corpo) {
        try {
            return recebe(jsonb.fromJson(corpo, LoteEventosDTO.class));
        } catch (JsonbException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("JSON inválido").build();
        }
    }
}
