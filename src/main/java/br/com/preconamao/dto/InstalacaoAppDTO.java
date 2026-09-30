package br.com.preconamao.dto;

import lombok.*;

// Corpo do POST /eventos/instalacao: enviado uma vez pelo app quando é instalado na tela inicial.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstalacaoAppDTO {

    // UUID gerado no aparelho (localStorage), o mesmo dos eventos de mídia.
    private String aparelhoId;

    // BOTAO ou NAVEGADOR.
    private String origem;

    // ANDROID, IOS ou OUTRA.
    private String plataforma;

}
