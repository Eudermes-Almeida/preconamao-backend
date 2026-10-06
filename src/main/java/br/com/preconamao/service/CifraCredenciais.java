package br.com.preconamao.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

// Cifra das credenciais das APIs das lojas (regra 12b do multi-loja): AES-256-GCM. A chave (32
// bytes em base64) vem SÓ da variável de ambiente CREDENCIAIS_CHAVE (credenciais.chave); o DES tem
// a sua, diferente da de produção. Gravado: base64(iv de 12 bytes + texto cifrado com o selo GCM).
@ApplicationScoped
public class CifraCredenciais {

    private static final int TAMANHO_IV = 12;
    private static final int TAMANHO_SELO_BITS = 128;

    @ConfigProperty(name = "credenciais.chave")
    Optional<String> chaveBase64;

    private final SecureRandom aleatorio = new SecureRandom();

    public String cifrar(String segredo) {
        try {
            byte[] iv = new byte[TAMANHO_IV];
            aleatorio.nextBytes(iv);
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.ENCRYPT_MODE, chave(), new GCMParameterSpec(TAMANHO_SELO_BITS, iv));
            byte[] cifrado = cifra.doFinal(segredo.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + cifrado.length).put(iv).put(cifrado).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Não foi possível cifrar a credencial", e);
        }
    }

    public String decifrar(String gravado) {
        try {
            byte[] tudo = Base64.getDecoder().decode(gravado);
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.DECRYPT_MODE, chave(), new GCMParameterSpec(TAMANHO_SELO_BITS, tudo, 0, TAMANHO_IV));
            return new String(cifra.doFinal(tudo, TAMANHO_IV, tudo.length - TAMANHO_IV), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Chave trocada ou dado adulterado: não revela nada além disso.
            throw new IllegalStateException("Credencial não pôde ser decifrada (chave do servidor mudou?)");
        }
    }

    private SecretKeySpec chave() {
        byte[] bytes = Base64.getDecoder().decode(chaveBase64
                .filter(c -> !c.isBlank())
                .orElseThrow(() -> new IllegalStateException("CREDENCIAIS_CHAVE não configurada neste servidor")));
        if (bytes.length != 32) {
            throw new IllegalStateException("CREDENCIAIS_CHAVE precisa ter 32 bytes (base64)");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
