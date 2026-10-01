package br.com.preconamao.dto;

import lombok.*;

// Chaves públicas da inscrição de push (base64url): p256dh = chave EC P-256 do aparelho; auth = segredo de 16 bytes.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChavesInscricaoDTO {

    private String p256dh;

    private String auth;

}
