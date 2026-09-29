package br.com.preconamao.dto;

import lombok.*;

// Corpo do sinal de vida do agente: hash SHA-256 (hex) do PRICETAB que está na pasta da loja
// (null se o arquivo não existir).
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SinalDTO {

    private String hash;

}
