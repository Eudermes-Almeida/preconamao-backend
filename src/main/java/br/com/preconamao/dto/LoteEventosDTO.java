package br.com.preconamao.dto;

import lombok.*;

import java.util.List;

// Corpo do POST /eventos: o app junta os eventos e envia a cada ~10 s (ou ao sair da tela).
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoteEventosDTO {

    // UUID gerado no aparelho (localStorage), sem nenhum dado pessoal.
    private String aparelhoId;

    private List<EventoMidiaDTO> eventos;
    // Loja escolhida no app (multi-loja); ausente (app antigo) = loja padrão.
    private Integer lojaId;

}
