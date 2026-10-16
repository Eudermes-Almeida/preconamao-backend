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

    // Lembrete da pré-lista por voz ("15 kg", "meia dúzia"), pela mesma chave da linha em itens
    // ou produtos. Opcional: app antigo não manda nem lê.
    private Map<String, String> lembretes;

    public ConteudoListaDTO(Map<String, Integer> itens, Map<String, ProdutoListaDTO> produtos) {
        this(itens, produtos, null);
    }

}
