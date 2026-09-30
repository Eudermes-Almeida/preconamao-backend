package br.com.preconamao.dto;

import lombok.*;

// POST /familia/listas: para quem (id do contato) e os itens.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EnviarListaDTO {

    private Long paraId;

    private ConteudoListaDTO conteudo;

}
