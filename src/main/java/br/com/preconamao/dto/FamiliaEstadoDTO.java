package br.com.preconamao.dto;

import lombok.*;

import java.util.List;

// GET /familia: o que o app consulta a cada ~30 s (nome, contatos e listas pendentes).
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FamiliaEstadoDTO {

    private String nome;

    private List<FamiliaContatoDTO> contatos;

    private List<ListaRecebidaDTO> recebidas;

}
