package br.com.preconamao.dto;

import lombok.*;

// Lista que este aparelho enviou (últimos 7 dias), para quem enviou acompanhar: aguardando
// (PENDENTE), juntada (ACEITA) ou recusada (RECUSADA). Como os ✓✓ do WhatsApp, mas da lista.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ListaEnviadaDTO {

    private Long id;

    private Long paraId;

    // Como quem enviou chama o destinatário ("Marido").
    private String apelido;

    private Integer quantidadeItens;

    private String situacao;

    private String enviadaEm;

    private String resolvidaEm;

}
