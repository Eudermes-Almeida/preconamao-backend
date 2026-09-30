package br.com.preconamao.dto;

import lombok.*;

// Uma pessoa ligada a este aparelho: o id serve para enviar e remover.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FamiliaContatoDTO {

    private Long id;

    // Como este aparelho a chama ("Marido").
    private String apelido;

    // O nome que ela mesma digitou ("João").
    private String nome;

}
