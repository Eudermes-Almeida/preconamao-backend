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

    // Pré-lista por voz: jeitos de falar o item (pre_lista_fala, minúsculas e sem acento) e os
    // termos de descrição em minúsculas (sem os de curinga). O app casa a fala do cliente com o
    // nome, as falas e os termos; fala vence termo quando os dois casam igual.
    private List<String> falas;

    // Marcas faladas do item (pre_lista_fala.marca, script 041): casam como as falas e o app
    // guarda a marca no lembrete ("Coca Cola - 2 L").
    private List<String> marcas;

    private List<String> termos;

}
