package br.com.preconamao.service;

import br.com.preconamao.entity.FamiliaAvisoInscricaoEntity;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.persistence.EntityManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Aviso no celular da "Família" (Web Push), mesmo com o app fechado: "Maria enviou 3 itens" e
// "Marido aceitou seu convite". O aviso só sai depois que a transação gravou a lista (senão o
// celular poderia abrir o app antes de a lista existir) e numa thread à parte (quem envia a lista
// não espera o serviço de push responder).
@ApplicationScoped
public class AvisoFamiliaService {

    // Payload no formato que o service worker do Angular (ngsw) entende: ele mesmo mostra a
    // notificação e, no toque, abre o app (ou traz a aba aberta para a frente).
    public record Destino(Long id, Long membroId, String endpoint, String p256dh, String auth) {
    }

    public record AvisoFamiliaEvento(List<Destino> destinos, String conteudoJson) {
    }

    @Inject
    EntityManager entityManager;

    @Inject
    WebPushService webPush;

    @Inject
    Event<AvisoFamiliaEvento> evento;

    private final Jsonb jsonb = JsonbBuilder.create();
    private final ExecutorService envios = Executors.newFixedThreadPool(2, tarefa -> {
        Thread thread = new Thread(tarefa, "aviso-familia");
        thread.setDaemon(true);
        return thread;
    });

    // Chamado dentro da transação de quem gerou o aviso; o envio de fato fica para depois do commit.
    public void avisar(Long membroId, String titulo, String texto, String tag) {
        if (!webPush.habilitado()) {
            return;
        }
        List<Destino> destinos = entityManager.createQuery(
                        "SELECT i FROM FamiliaAvisoInscricaoEntity i WHERE i.membroId = :membro", FamiliaAvisoInscricaoEntity.class)
                .setParameter("membro", membroId)
                .getResultStream()
                .map(i -> new Destino(i.getId(), i.getMembroId(), i.getEndpoint(), i.getP256dh(), i.getAuth()))
                .toList();
        if (!destinos.isEmpty()) {
            evento.fire(new AvisoFamiliaEvento(destinos, conteudo(titulo, texto, tag)));
        }
    }

    void aposGravar(@Observes(during = TransactionPhase.AFTER_SUCCESS) AvisoFamiliaEvento aviso) {
        envios.submit(() -> enviar(aviso));
    }

    private void enviar(AvisoFamiliaEvento aviso) {
        List<Long> extintas = new ArrayList<>();
        Set<Long> membrosAfetados = new HashSet<>();
        int entregues = 0;
        for (Destino destino : aviso.destinos()) {
            WebPushService.Resultado resultado = webPush.enviar(destino.endpoint(), destino.p256dh(), destino.auth(), aviso.conteudoJson());
            if (resultado == WebPushService.Resultado.ENTREGUE) {
                entregues++;
            } else if (resultado == WebPushService.Resultado.INSCRICAO_EXTINTA) {
                extintas.add(destino.id());
                membrosAfetados.add(destino.membroId());
            }
        }
        Log.infof("Aviso da Família: %d de %d entregue(s) ao serviço de push, %d inscrição(ões) extinta(s)",
                entregues, aviso.destinos().size(), extintas.size());
        // App desinstalado, dados do navegador apagados, avisos bloqueados... Sem nenhuma inscrição
        // viva, o celular fica marcado como "app removido" até dar sinal de vida (ver buscarMembro).
        if (!extintas.isEmpty()) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    entityManager.createQuery("DELETE FROM FamiliaAvisoInscricaoEntity i WHERE i.id IN :ids")
                            .setParameter("ids", extintas)
                            .executeUpdate();
                    entityManager.createQuery("UPDATE FamiliaMembroEntity m SET m.appRemovidoEm = :agora "
                                    + "WHERE m.id IN :membros AND NOT EXISTS "
                                    + "(SELECT i FROM FamiliaAvisoInscricaoEntity i WHERE i.membroId = m.id)")
                            .setParameter("agora", OffsetDateTime.now())
                            .setParameter("membros", membrosAfetados)
                            .executeUpdate();
                });
            } catch (Exception e) {
                Log.warn("Não foi possível apagar inscrições de aviso extintas", e);
            }
        }
    }

    private String conteudo(String titulo, String texto, String tag) {
        Map<String, Object> abrir = Map.of("operation", "navigateLastFocusedOrOpen", "url", "/");
        Map<String, Object> notificacao = new LinkedHashMap<>();
        notificacao.put("title", titulo);
        notificacao.put("body", texto);
        notificacao.put("icon", "/assets/icons/icon-192x192.png");
        notificacao.put("badge", "/assets/icons/icon-96x96.png");
        notificacao.put("lang", "pt-BR");
        notificacao.put("tag", tag);
        notificacao.put("renotify", true);
        notificacao.put("data", Map.of("onActionClick", Map.of("default", abrir)));
        return jsonb.toJson(Map.of("notification", notificacao));
    }

    @PreDestroy
    void encerrar() {
        envios.shutdown();
    }
}
