package br.com.preconamao.service;

import br.com.preconamao.entity.CargaPricetabEntity;
import br.com.preconamao.entity.LojaEntity;
import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// Vigia as lojas com a proteção ligada e avisa por e-mail quando o preço do app pode estar
// desatualizado: agente sem sinal, carga retida ou com erro, arquivo da loja não aplicado. Avisa ao
// começar, repete a cada REPETIR_HORAS enquanto durar e avisa quando normaliza.
@ApplicationScoped
public class AlertaService {

    private static final int REPETIR_HORAS = 6;
    // Tempo que o arquivo novo da loja pode ficar sem ser aplicado antes de virar alerta (a carga
    // normal leva segundos).
    private static final int TOLERANCIA_NAO_APLICADO_MIN = 10;

    // Vazio = só registra no log (e-mail não configurado).
    @ConfigProperty(name = "alerta.email-destino")
    Optional<String> emailDestino;

    @Inject
    EntityManager entityManager;

    @Inject
    LojaService lojaService;

    @Inject
    CargaPricetabService cargaService;

    @Inject
    Mailer mailer;

    @Scheduled(every = "1m", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void verificarLojas() {
        List<LojaEntity> lojas = entityManager.createQuery(
                "SELECT l FROM LojaEntity l WHERE l.limiteSemSinalMin IS NOT NULL", LojaEntity.class).getResultList();
        for (LojaEntity loja : lojas) {
            String problema = problemaAtual(loja);
            boolean mudou = !Objects.equals(problema, loja.getAlertaAtivo());
            boolean repetir = problema != null && loja.getAlertaEnviadoEm() != null
                    && loja.getAlertaEnviadoEm().isBefore(OffsetDateTime.now().minusHours(REPETIR_HORAS));
            if (!mudou && !repetir) {
                continue;
            }
            avisar(loja, problema);
            loja.setAlertaAtivo(problema);
            loja.setAlertaEnviadoEm(OffsetDateTime.now());
        }
    }

    // null = tudo certo.
    String problemaAtual(LojaEntity loja) {
        if (!lojaService.sinalRecente(loja)) {
            return "SEM_SINAL";
        }
        if (loja.getHashAplicado() != null && loja.getHashAplicado().equals(loja.getHashInformado())) {
            return null;
        }
        CargaPricetabEntity ultima = cargaService.ultimaCarga(loja.getId());
        if (ultima != null && ultima.getHash().equals(loja.getHashInformado())) {
            if (CargaPricetabEntity.RETIDA.equals(ultima.getSituacao())) {
                return "CARGA_RETIDA";
            }
            if (CargaPricetabEntity.ERRO.equals(ultima.getSituacao())) {
                return "CARGA_COM_ERRO";
            }
            if (CargaPricetabEntity.RECEBIDA.equals(ultima.getSituacao())
                    && ultima.getRecebidaEm().isAfter(OffsetDateTime.now().minusMinutes(TOLERANCIA_NAO_APLICADO_MIN))) {
                return null;
            }
        }
        return "ARQUIVO_NAO_APLICADO";
    }

    private void avisar(LojaEntity loja, String problema) {
        String assunto;
        String texto;
        if (problema == null) {
            assunto = "[Simplifica Compras] " + loja.getNome() + ": preços normalizados";
            texto = "O problema anterior (" + loja.getAlertaAtivo() + ") foi resolvido. O app voltou a exibir os preços.";
        } else {
            assunto = "[Simplifica Compras] ALERTA " + loja.getNome() + ": " + problema;
            texto = descricao(problema, loja) + "\n\nEnquanto isso, o app mostra \"Consulte o preço no terminal "
                    + "da loja\" no lugar do preço.\nÚltimo sinal do agente: "
                    + (loja.getUltimoSinalEm() == null ? "nunca" : loja.getUltimoSinalEm());
        }
        Log.warnf("%s | %s", assunto, texto.replace('\n', ' '));
        if (emailDestino.isEmpty() || emailDestino.get().isBlank()) {
            return;
        }
        try {
            mailer.send(Mail.withText(emailDestino.get(), assunto, texto));
        } catch (Exception e) {
            Log.error("Falha ao enviar e-mail de alerta", e);
        }
    }

    private String descricao(String problema, LojaEntity loja) {
        return switch (problema) {
            case "SEM_SINAL" -> "O agente da loja está sem dar sinal de vida há mais de " + loja.getLimiteSemSinalMin()
                    + " minutos. Verifique se o PC/servidor da loja está ligado, com internet, e se a tarefa "
                    + "\"SimplificaCompras-AgentePricetab\" está rodando.";
            case "CARGA_RETIDA" -> "O último PRICETAB da loja foi RETIDO por segurança (muitos produtos sumiriam "
                    + "de uma vez: arquivo cortado ou vazio?). Nada foi alterado. Confira o arquivo na loja.";
            case "CARGA_COM_ERRO" -> "O último PRICETAB da loja deu erro ao ser aplicado. Nada foi alterado.";
            default -> "A loja tem um PRICETAB diferente do que está aplicado no app, e ele não chegou ao servidor.";
        };
    }
}
