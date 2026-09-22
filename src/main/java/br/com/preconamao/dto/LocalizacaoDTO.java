package br.com.preconamao.dto;

import lombok.*;

// Onde encontrar o produto na loja piloto (layout_posicao). Só existe no ProdutoDTO
// quando o produto tem layout_id — sem isso, o front não mostra a seção de localização.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LocalizacaoDTO {

    private String nomeSetor;

    private Integer rua;

    private Integer quarteirao;

    // ESQUERDA, DIREITA ou CENTRO (balcão de atendimento, sem lado).
    private String lado;

}
