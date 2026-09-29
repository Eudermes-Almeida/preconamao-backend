package br.com.preconamao.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EventoRecenteDTO {

    private String registradoEm;

    private String tipo;

    private String origem;

    private String codigoBarras;

    private String descricao;

}
