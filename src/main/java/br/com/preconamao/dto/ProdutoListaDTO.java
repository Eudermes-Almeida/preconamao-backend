package br.com.preconamao.dto;

import lombok.*;

// Produto exato de oferta dentro de uma lista da "Família".
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProdutoListaDTO {

    private String descricao;

    private Integer quantidade;

}
