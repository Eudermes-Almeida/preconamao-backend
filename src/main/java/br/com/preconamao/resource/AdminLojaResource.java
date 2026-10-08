package br.com.preconamao.resource;

import br.com.preconamao.entity.CredencialApiEntity;
import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.service.CifraCredenciais;
import br.com.preconamao.service.ColetaApiService;
import br.com.preconamao.service.RelatorioAcessoService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

// Operações de quem opera o sistema sobre uma loja. Só com a chave GERAL (X-Chave-Relatorio).
// Por enquanto: cadastrar a credencial da API da loja (cifrada; nunca devolvida) e pedir uma
// coleta agora (laboratório). O cadastro de lojas continua por script SQL (regra 27).
@Path("/admin/lojas")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Administração de lojas", description = "Só com a chave geral")
public class AdminLojaResource {

    @Inject
    RelatorioAcessoService acessoService;

    @Inject
    CifraCredenciais cifra;

    @Inject
    ColetaApiService coletaApiService;

    @Inject
    EntityManager entityManager;

    // unidade: como a loja é identificada no sistema de gestão (RPInfo: CNPJ da unidade); opcional.
    public record CredencialApiDTO(String url, String usuario, String senha, String unidade) {
    }

    @PUT
    @Path("/{id}/credencial-api")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Grava a credencial da API da loja", description = "A senha/token é cifrada no servidor e nunca volta em nenhuma resposta.")
    public Response gravarCredencial(@HeaderParam("X-Chave-Relatorio") String chave, @PathParam("id") Integer lojaId,
                                     CredencialApiDTO dados) {
        if (!geral(chave)) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Só com a chave geral").build();
        }
        if (dados == null || vazio(dados.url()) || vazio(dados.usuario()) || vazio(dados.senha())
                || !dados.url().matches("https?://.+")) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Informe url (http/https), usuario e senha").build();
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            LojaEntity loja = entityManager.find(LojaEntity.class, lojaId);
            if (loja == null) {
                return Response.status(Response.Status.NOT_FOUND).entity("Loja não encontrada").build();
            }
            CredencialApiEntity credencial = Optional.ofNullable(entityManager.find(CredencialApiEntity.class, lojaId))
                    .orElseGet(() -> CredencialApiEntity.builder().lojaId(lojaId).build());
            credencial.setUrl(dados.url().trim());
            credencial.setUsuario(dados.usuario().trim());
            credencial.setUnidade(vazio(dados.unidade()) ? null : dados.unidade().trim());
            credencial.setSegredoCifrado(cifra.cifrar(dados.senha()));
            credencial.setAtualizadaEm(OffsetDateTime.now());
            entityManager.merge(credencial);
            return Response.ok(Map.of("loja", lojaId, "url", credencial.getUrl(), "usuario", credencial.getUsuario(),
                    "unidade", String.valueOf(credencial.getUnidade()), "gravada", true)).build();
        });
    }

    @POST
    @Path("/{id}/coletar-agora")
    @Operation(summary = "Coleta a API da loja agora (sem esperar o intervalo)",
            description = "Coleta completa; ?tipo=incremental faz a incremental (só o que mudou), se a loja já puder.")
    public Response coletarAgora(@HeaderParam("X-Chave-Relatorio") String chave, @PathParam("id") Integer lojaId,
                                 @QueryParam("tipo") String tipo) {
        if (!geral(chave)) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Só com a chave geral").build();
        }
        return Response.ok(Map.of("resultado", coletaApiService.coletar(lojaId, !"incremental".equalsIgnoreCase(tipo)))).build();
    }

    @POST
    @Path("/{id}/pedir-completa")
    @Operation(summary = "Pede uma coleta completa ao agente RPInfo da loja",
            description = "Só marca: na próxima vez que o agente enviar o sinal (conexão de saída da loja), a resposta traz fazerCompleta=true.")
    public Response pedirCompleta(@HeaderParam("X-Chave-Relatorio") String chave, @PathParam("id") Integer lojaId) {
        return marcarLoja(chave, lojaId, loja -> loja.setPedirCompleta(true), "pedirCompleta");
    }

    @POST
    @Path("/{id}/liberar-agente")
    @Operation(summary = "Libera o registro do agente da loja (troca de computador)",
            description = "Só 1 agente por loja: o próximo agente que se identificar passa a ser o registrado.")
    public Response liberarAgente(@HeaderParam("X-Chave-Relatorio") String chave, @PathParam("id") Integer lojaId) {
        return marcarLoja(chave, lojaId, loja -> loja.setAgenteId(null), "agenteLiberado");
    }

    private Response marcarLoja(String chave, Integer lojaId, java.util.function.Consumer<LojaEntity> marcacao, String campo) {
        if (!geral(chave)) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Só com a chave geral").build();
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            LojaEntity loja = entityManager.find(LojaEntity.class, lojaId);
            if (loja == null) {
                return Response.status(Response.Status.NOT_FOUND).entity("Loja não encontrada").build();
            }
            marcacao.accept(loja);
            return Response.ok(Map.of("loja", lojaId, campo, true)).build();
        });
    }

    private boolean geral(String chave) {
        return acessoService.autenticar(chave).map(a -> RelatorioAcessoService.GERAL.equals(a.tipo())).orElse(false);
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }
}
