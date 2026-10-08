package br.com.preconamao.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SinalRespostaDTO {

    // true: o servidor não tem o arquivo que o agente tem (envio perdido); o agente reenvia.
    private boolean enviarArquivo;

    // Agente RPInfo (scripts/034): o servidor pede uma coleta COMPLETA. Só uma marcação: o agente
    // decide e executa sozinho; nunca recebe comando, endereço ou código.
    private boolean fazerCompleta;

    private String hashAplicado;

    private CargaRecebidaDTO ultimaCarga;

}
