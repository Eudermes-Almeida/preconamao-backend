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

}
