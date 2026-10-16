package br.com.preconamao.dto;

import lombok.*;

import java.util.List;

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

    // Pré-lista por voz: jeitos de falar o item (pre_lista_fala, minúsculas e sem acento) e os
    // termos de descrição em minúsculas (sem os de curinga). O app casa a fala do cliente com o
    // nome, as falas e os termos; fala vence termo quando os dois casam igual.
    private List<String> falas;

    private List<String> termos;

}
