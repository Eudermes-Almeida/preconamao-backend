package br.com.preconamao.dto;

import lombok.*;

// POST /familia/convites: como quem convida vai chamar quem aceitar ("Marido").
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NovoConviteDTO {

    private String apelido;

}
