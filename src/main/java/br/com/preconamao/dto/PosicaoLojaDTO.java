package br.com.preconamao.dto;

import lombok.*;

// Posição medida pelo celular de dentro da loja (botão "Registrar a posição desta loja" do /admin).
// raioM é opcional: sem ele, o raio atual da loja continua valendo.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PosicaoLojaDTO {

    private Double latitude;

    private Double longitude;

    private Integer raioM;

}
