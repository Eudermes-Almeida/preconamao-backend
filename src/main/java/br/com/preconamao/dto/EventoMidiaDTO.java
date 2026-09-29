package br.com.preconamao.dto;

import lombok.*;

// Um evento dentro do lote enviado pelo app (ver LoteEventosDTO).
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EventoMidiaDTO {

    // EXIBICAO, FAVORITAR, DESFAVORITAR, LOCALIZAR ou PRE_LISTA.
    private String tipo;

    // ANUNCIO ou TELA_OFERTAS.
    private String origem;

    private String codigoBarras;

}
