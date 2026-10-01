package br.com.preconamao.dto;

import lombok.*;

import java.util.List;

// GET /familia: o que o app consulta a cada ~30 s (nome, contatos, listas pendentes e a situação
// das listas que este aparelho enviou).
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FamiliaEstadoDTO {

    private String nome;

    private List<FamiliaContatoDTO> contatos;

    private List<ListaRecebidaDTO> recebidas;

    private List<ListaEnviadaDTO> enviadas;

}
