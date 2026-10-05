package br.com.preconamao.resource;

import br.com.preconamao.dto.ProdutoDTO;
import br.com.preconamao.service.ProdutoService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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

import java.util.Arrays;

@Path("/produtos")
public class ProdutoResource {

    // Total de produtos parecidos, além dos até 10 devolvidos na lista (o app pede para o cliente
    // falar também a marca quando há mais). Liberado no CORS (quarkus.http.cors.exposed-headers).
    static final String CABECALHO_TOTAL = "X-Total-Encontrados";

    @Inject
    ProdutoService produtoService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Busca Produto por Descrição", description = "Busca candidatos a partir da descrição falada pelo cliente")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Lista de candidatos (vazia se nada parecido foi encontrado)", content = @Content(schema = @Schema(implementation = ProdutoDTO.class))),
            @APIResponse(responseCode = "400", description = "Descrição não informada"),
            @APIResponse(responseCode = "500", description = "Erro interno do servidor"),
    })
    @Operation(summary = "Busca produtos por descrição", description = "Recebe o texto reconhecido por voz e retorna até 10 produtos com descrição parecida, do mais para o menos similar; o cabeçalho X-Total-Encontrados traz quantos foram achados ao todo.")
    public Response buscaProdutosPorDescricao(@QueryParam("descricao") String descricao) {
        try {
            if (descricao == null || descricao.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("A descrição é obrigatória")
                        .build();
            }

            ProdutoService.ResultadoBusca resultado = produtoService.buscaPorDescricao(descricao);
            return Response.ok(resultado.produtos())
                    .header(CABECALHO_TOTAL, resultado.totalEncontrado())
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro ao buscar produtos: " + e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/lote")
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Busca Produtos em Lote", description = "Preço atual de vários produtos numa chamada só")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Produtos encontrados (os não encontrados ou inativos ficam de fora)", content = @Content(schema = @Schema(implementation = ProdutoDTO.class))),
            @APIResponse(responseCode = "400", description = "Nenhum código informado"),
            @APIResponse(responseCode = "500", description = "Erro interno do servidor"),
    })
    @Operation(summary = "Busca produtos por lista de códigos", description = "Recebe até 100 códigos separados por vírgula (ofertas do app, revalidação do carrinho) e devolve os produtos com o preço atual.")
    public Response buscaProdutosPorCodigos(@QueryParam("codigos") String codigos) {
        try {
            if (codigos == null || codigos.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Informe os códigos separados por vírgula")
                        .build();
            }

            return Response.ok(produtoService.buscaPorCodigos(Arrays.asList(codigos.split(",")))).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro ao buscar produtos: " + e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/{codigoBarras}")
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Busca Produto por Código de Barras", description = "Busca produto e preço a partir da leitura do scanner")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Produto encontrado", content = @Content(schema = @Schema(implementation = ProdutoDTO.class))),
            @APIResponse(responseCode = "400", description = "Código de barras não informado"),
            @APIResponse(responseCode = "404", description = "Nenhum produto encontrado para o código informado"),
            @APIResponse(responseCode = "500", description = "Erro interno do servidor"),
    })
    @Operation(summary = "Busca produto por código de barras", description = "Recebe o código lido pelo scanner USB e retorna descrição e preço (em centavos) do produto correspondente.")
    public Response buscaProdutoPorCodigoBarras(@PathParam("codigoBarras") String codigoBarras) {
        try {
            if (codigoBarras == null || codigoBarras.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("O código de barras é obrigatório")
                        .build();
            }

            return produtoService.buscaPorCodigoBarras(codigoBarras)
                    .map(produto -> Response.ok(produto).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                            .entity("Nenhum produto encontrado para o código " + codigoBarras)
                            .build());
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erro ao buscar produto: " + e.getMessage())
                    .build();
        }
    }

}
