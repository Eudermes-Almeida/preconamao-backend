package br.com.preconamao.dto;

import lombok.*;

// POST /familia/convites/{codigo}/aceitar: como quem aceita vai chamar quem convidou ("Esposa").
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AceitarConviteDTO {

    private String apelido;

}
