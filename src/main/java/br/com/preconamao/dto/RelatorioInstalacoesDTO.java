package br.com.preconamao.dto;

import lombok.*;

// Instalações do app no período do relatório (aparelhos distintos; reinstalar não conta de novo).
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RelatorioInstalacoesDTO {

    private long total;

    // Pelo nosso botão "Instalar o app" x pelo menu do navegador.
    private long botao;

    private long navegador;

    private long android;

    private long ios;

    private long outras;

}
