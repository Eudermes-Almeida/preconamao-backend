package br.com.preconamao.dto;

import lombok.*;

// PUT /familia/avisos e POST /familia/avisos/cancelar: o JSON de PushSubscription.toJSON() do
// navegador ({"endpoint": "...", "keys": {"p256dh": "...", "auth": "..."}}).
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InscricaoAvisoDTO {

    private String endpoint;

    private ChavesInscricaoDTO keys;

}
