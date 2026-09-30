package br.com.preconamao.dto;

import lombok.*;

// Lista pendente recebida de um contato, aguardando "Juntar" ou "Recusar".
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ListaRecebidaDTO {

    private Long id;

    private Long contatoId;

    private String apelido;

    private String nome;

    private ConteudoListaDTO conteudo;

    private Integer quantidadeItens;

    private String enviadaEm;

}
