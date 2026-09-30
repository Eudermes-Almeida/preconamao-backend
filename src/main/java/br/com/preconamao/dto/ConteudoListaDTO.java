package br.com.preconamao.dto;

import lombok.*;

import java.util.Map;

// Itens de uma lista da "Família", no mesmo formato da pré-lista do app: itens genéricos por id (-> quantidade) e produtos exatos de oferta por código de barras.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConteudoListaDTO {

    private Map<String, Integer> itens;

    private Map<String, ProdutoListaDTO> produtos;

}
