package br.com.preconamao.dto;

import lombok.*;

// Loja parceira como o app a vê (dados públicos, sem chaves): o celular calcula a distância até
// cada uma e só deixa escolher as que alcançam a posição dele (scripts/023).
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LojaPublicaDTO {

    private Integer id;

    // Fim do endereço do QR code: www.simplificacompras.app.br/<slug>.
    private String slug;

    // Nome curto, que cabe no topo da tela do celular.
    private String nome;

    private double latitude;

    private double longitude;

    // Entrada: até onde a loja aparece para ser escolhida pela localização.
    private int raioM;

    // Saída: até onde a escolha continua valendo (o app só tira a loja com leituras fora dele).
    private int raioSaidaM;

    // De onde vêm os preços (PRICETAB ou API) e o nome da ficha do formato: o seletor de loja do
    // ambiente de testes mostra, para saber de onde vem cada preço.
    private String origem;

    private String formato;

}
