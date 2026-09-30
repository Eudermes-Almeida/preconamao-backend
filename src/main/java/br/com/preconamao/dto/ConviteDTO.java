package br.com.preconamao.dto;

import lombok.*;

// Convite da "Família": o código (vai no link) e, para quem abre o link, quem convidou e a situação.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConviteDTO {

    private String codigo;

    // Nome de quem convidou.
    private String deNome;

    // VALIDO, USADO, VENCIDO ou PROPRIO (o aparelho abriu o próprio convite).
    private String situacao;

    private String expiraEm;

}
