package br.com.preconamao.dto;

import lombok.*;

// GET /familia/avisos/chave: chave pública VAPID (base64url) que o app usa para se inscrever; null = avisos desligados no servidor.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChaveAvisoDTO {

    private String chavePublica;

}
