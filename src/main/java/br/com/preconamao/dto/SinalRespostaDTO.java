package br.com.preconamao.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SinalRespostaDTO {

    // true: o servidor não tem o arquivo que o agente tem (envio perdido); o agente reenvia.
    private boolean enviarArquivo;

    private String hashAplicado;

    private CargaRecebidaDTO ultimaCarga;

}
