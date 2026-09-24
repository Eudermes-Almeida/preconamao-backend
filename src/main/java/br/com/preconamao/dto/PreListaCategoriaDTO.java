package br.com.preconamao.dto;

import lombok.*;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PreListaCategoriaDTO {

    private Long id;

    private String nome;

    // Na ordem da planilha de origem (pre_lista_item.ordem).
    private List<PreListaItemDTO> itens;

}
