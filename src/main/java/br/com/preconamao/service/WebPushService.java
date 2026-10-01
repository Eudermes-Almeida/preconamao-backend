package br.com.preconamao.service;

import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

// Envio de Web Push sem biblioteca externa, só com o que o Java 17 já traz:
// - conteúdo cifrado como manda a RFC 8291 (aes128gcm): só o aparelho consegue ler o aviso, nem o
//   serviço de push do navegador (Google, Apple, Mozilla) vê o texto;
// - identificação do servidor pela RFC 8292 (VAPID): um JWT ES256 assinado com a nossa chave privada.
// Sem as chaves VAPID configuradas, os avisos ficam desligados e o app esconde o botão.
@ApplicationScoped
public class WebPushService {

    // Só serviços de push conhecidos: a inscrição vem do app, e sem esta lista qualquer um poderia
    // fazer o servidor mandar requisições para um endereço qualquer.
    private static final List<String> SERVICOS_PERMITIDOS = List.of(
            "fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com");

    // Quanto tempo o serviço de push guarda o aviso se o celular estiver desligado ou sem rede.
    private static final int TTL_SEGUNDOS = 24 * 60 * 60;
    private static final int TAMANHO_REGISTRO = 4096;

    @ConfigProperty(name = "push.vapid.chave-publica")
    Optional<String> chavePublica;

    @ConfigProperty(name = "push.vapid.chave-privada")
    Optional<String> chavePrivada;

    @ConfigProperty(name = "push.vapid.assunto", defaultValue = "https://www.simplificacompras.app.br")
    String assunto;

    private final SecureRandom aleatorio = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private ECParameterSpec curva;
    private ECPrivateKey privadaVapid;

    // Resultado de um envio: o app só precisa saber se a inscrição morreu (para apagá-la).
    public enum Resultado { ENTREGUE, INSCRICAO_EXTINTA, FALHA }

    @PostConstruct
    void carregarChaves() {
        try {
            AlgorithmParameters parametros = AlgorithmParameters.getInstance("EC");
            parametros.init(new ECGenParameterSpec("secp256r1"));
            curva = parametros.getParameterSpec(ECParameterSpec.class);
            if (habilitado()) {
                privadaVapid = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(
                        new ECPrivateKeySpec(new BigInteger(1, b64(chavePrivada.get())), curva));
            }
        } catch (Exception e) {
            Log.error("Chaves VAPID inválidas: avisos da Família desligados", e);
            privadaVapid = null;
        }
    }

    public boolean habilitado() {
        return chavePublica.filter(c -> !c.isBlank()).isPresent() && chavePrivada.filter(c -> !c.isBlank()).isPresent();
    }

    // Para o app: null quando os avisos estão desligados.
    public String chavePublicaParaApp() {
        return habilitado() && privadaVapid != null ? chavePublica.get().trim() : null;
    }

    // Validação da inscrição que o app manda: endereço de serviço conhecido e chaves com o tamanho certo.
    public boolean inscricaoValida(String endpoint, String p256dh, String auth) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && endpoint.length() <= 1000
                    && SERVICOS_PERMITIDOS.stream().anyMatch(s -> host.equals(s) || host.endsWith("." + s))
                    && b64(p256dh).length == 65 && b64(auth).length == 16;
        } catch (Exception e) {
            return false;
        }
    }

    public Resultado enviar(String endpoint, String p256dh, String auth, String conteudoJson) {
        if (privadaVapid == null) {
            return Resultado.FALHA;
        }
        try {
            byte[] corpo = cifrar(conteudoJson.getBytes(StandardCharsets.UTF_8), b64(p256dh), b64(auth));
            URI uri = URI.create(endpoint);
            HttpRequest requisicao = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/octet-stream")
                    .header("Content-Encoding", "aes128gcm")
                    .header("TTL", String.valueOf(TTL_SEGUNDOS))
                    .header("Urgency", "high")
                    .header("Authorization", "vapid t=" + jwt(uri.getScheme() + "://" + uri.getHost()) + ", k=" + chavePublica.get().trim())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(corpo))
                    .build();
            HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
            int status = resposta.statusCode();
            if (status >= 200 && status < 300) {
                return Resultado.ENTREGUE;
            }
            if (status == 404 || status == 410) {
                return Resultado.INSCRICAO_EXTINTA;
            }
            Log.warnf("Push recusado (%d) por %s: %s", status, uri.getHost(), resposta.body());
            return Resultado.FALHA;
        } catch (Exception e) {
            Log.warn("Falha ao enviar push", e);
            return Resultado.FALHA;
        }
    }

    // ------------------------------------------------------------------------------------------
    // RFC 8291: cifra o conteúdo para a chave do aparelho (um registro só, sem preenchimento).
    // ------------------------------------------------------------------------------------------

    byte[] cifrar(byte[] conteudo, byte[] chaveAparelho, byte[] segredoAuth) throws Exception {
        KeyPairGenerator gerador = KeyPairGenerator.getInstance("EC");
        gerador.initialize(curva, aleatorio);
        KeyPair efemera = gerador.generateKeyPair();
        byte[] publicaEfemera = semCompressao((ECPublicKey) efemera.getPublic());

        KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
        ecdh.init(efemera.getPrivate());
        ecdh.doPhase(publicaDoAparelho(chaveAparelho), true);
        byte[] segredoEcdh = ecdh.generateSecret();

        byte[] prkChave = hmac(segredoAuth, segredoEcdh);
        byte[] infoChave = concatenar("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, chaveAparelho, publicaEfemera);
        byte[] ikm = hmac(prkChave, concatenar(infoChave, new byte[]{1}));

        byte[] sal = new byte[16];
        aleatorio.nextBytes(sal);
        byte[] prk = hmac(sal, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concatenar("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concatenar("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 12);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        // 0x02 = delimitador do último (e único) registro.
        byte[] cifrado = aes.doFinal(concatenar(conteudo, new byte[]{2}));

        ByteBuffer cabecalho = ByteBuffer.allocate(16 + 4 + 1 + publicaEfemera.length);
        cabecalho.put(sal).putInt(TAMANHO_REGISTRO).put((byte) publicaEfemera.length).put(publicaEfemera);
        return concatenar(cabecalho.array(), cifrado);
    }

    // ------------------------------------------------------------------------------------------
    // RFC 8292: JWT ES256 com o destino (aud), validade (exp) e contato (sub).
    // ------------------------------------------------------------------------------------------

    private String jwt(String audiencia) throws Exception {
        String cabecalho = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        long expira = Instant.now().plus(Duration.ofHours(12)).getEpochSecond();
        String corpo = b64(("{\"aud\":\"" + audiencia + "\",\"exp\":" + expira + ",\"sub\":\"" + assunto + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        Signature assinatura = Signature.getInstance("SHA256withECDSA");
        assinatura.initSign(privadaVapid);
        assinatura.update((cabecalho + "." + corpo).getBytes(StandardCharsets.US_ASCII));
        return cabecalho + "." + corpo + "." + b64(derParaBruta(assinatura.sign()));
    }

    // O Java assina em DER (30 len 02 lenR R 02 lenS S; em P-256 "len" sempre cabe em 1 byte); o
    // JWT quer R e S lado a lado, 32 bytes cada.
    private static byte[] derParaBruta(byte[] der) {
        int posicao = 3;
        int tamanhoR = der[posicao];
        byte[] r = Arrays.copyOfRange(der, posicao + 1, posicao + 1 + tamanhoR);
        posicao += 1 + tamanhoR + 1;
        int tamanhoS = der[posicao];
        byte[] s = Arrays.copyOfRange(der, posicao + 1, posicao + 1 + tamanhoS);
        return concatenar(ajustar32(r), ajustar32(s));
    }

    private static byte[] ajustar32(byte[] numero) {
        byte[] saida = new byte[32];
        int copiar = Math.min(numero.length, 32);
        System.arraycopy(numero, numero.length - copiar, saida, 32 - copiar, copiar);
        return saida;
    }

    // ------------------------------------------------------------------------------------------

    private ECPublicKey publicaDoAparelho(byte[] semCompressao) throws Exception {
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(semCompressao, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(semCompressao, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), curva));
    }

    private static byte[] semCompressao(ECPublicKey chave) {
        return concatenar(new byte[]{4}, ajustar32(chave.getW().getAffineX().toByteArray()), ajustar32(chave.getW().getAffineY().toByteArray()));
    }

    private static byte[] hmac(byte[] chave, byte[] dados) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(chave, "HmacSHA256"));
        return mac.doFinal(dados);
    }

    private static byte[] concatenar(byte[]... partes) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        for (byte[] parte : partes) {
            saida.writeBytes(parte);
        }
        return saida.toByteArray();
    }

    private static byte[] b64(String texto) {
        return Base64.getUrlDecoder().decode(texto.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    private static String b64(byte[] dados) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(dados);
    }
}
