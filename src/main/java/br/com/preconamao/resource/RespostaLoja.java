package br.com.preconamao.resource;

import br.com.preconamao.service.LojaService;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

// Resposta padrão para a loja pedida pelo app (?loja=) que não pode ser atendida: 400 sem loja
// (só no DES) ou 404 loja inexistente/inativa. O app mostra a mensagem e abre a escolha de loja.
final class RespostaLoja {

    private RespostaLoja() {
    }

    static Response indisponivel(LojaService.LojaIndisponivel e) {
        return Response.status(e.status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("mensagem", e.getMessage(), "lojaIndisponivel", true))
                .build();
    }
}
