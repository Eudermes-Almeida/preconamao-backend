package br.com.preconamao.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PreListaItemDTO {

    // Mesmo valor de ProdutoDTO.preListaItemId: é por ele que o front risca o item da lista.
    private Long id;

    private String nome;

    // A loja da consulta tem algum produto ativo ligado a este item (multi-loja, regras 3b e 25d):
    // o app esconde o item que a loja não tem — a não ser que ele já esteja marcado na lista do
    // cliente (ex.: lista recebida da Família), quando aparece com "Não encontrado nesta loja".
    private boolean disponivel;

}
