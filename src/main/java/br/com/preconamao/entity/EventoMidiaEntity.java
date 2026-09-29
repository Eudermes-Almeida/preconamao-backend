package br.com.preconamao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

// Interação do cliente com uma oferta (scripts/018_eventos_midia.sql): exibição, favoritar,
// localizar, incluir na pré-lista. Alimenta o relatório da aba administrativa.
@Entity
@Table(name = "evento_midia")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EventoMidiaEntity {

    public static final String EXIBICAO = "EXIBICAO";
    public static final String FAVORITAR = "FAVORITAR";
    public static final String DESFAVORITAR = "DESFAVORITAR";
    public static final String LOCALIZAR = "LOCALIZAR";
    public static final String PRE_LISTA = "PRE_LISTA";

    public static final String ANUNCIO = "ANUNCIO";
    public static final String TELA_OFERTAS = "TELA_OFERTAS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loja_id", nullable = false)
    private Integer lojaId;

    @Column(name = "tipo", nullable = false, length = 12)
    private String tipo;

    @Column(name = "origem", nullable = false, length = 12)
    private String origem;

    @Column(name = "codigo_barras", nullable = false, length = 14)
    private String codigoBarras;

    // Código aleatório gerado no aparelho, sem dado pessoal: conta aparelhos distintos (alcance).
    @Column(name = "aparelho_id", nullable = false)
    private UUID aparelhoId;

    // Hora do servidor ao receber o lote (o relógio do celular não é confiável).
    @Column(name = "registrado_em", nullable = false)
    private OffsetDateTime registradoEm;

}
