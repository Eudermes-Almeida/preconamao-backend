package br.com.preconamao.dto;

import lombok.*;

// PUT /familia/eu: o primeiro nome que a pessoa digitou ("Maria").
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FamiliaNomeDTO {

    private String nome;

}
