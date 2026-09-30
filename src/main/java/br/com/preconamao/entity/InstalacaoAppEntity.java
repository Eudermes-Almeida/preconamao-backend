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

// App instalado na tela inicial de um aparelho (scripts/019_instalacao_app.sql). Uma linha por
// aparelho e loja; alimenta o indicador "Instalações do app" da aba administrativa.
@Entity
@Table(name = "instalacao_app")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstalacaoAppEntity {

    public static final String BOTAO = "BOTAO";
    public static final String NAVEGADOR = "NAVEGADOR";

    public static final String ANDROID = "ANDROID";
    public static final String IOS = "IOS";
    public static final String OUTRA = "OUTRA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loja_id", nullable = false)
    private Integer lojaId;

    @Column(name = "aparelho_id", nullable = false)
    private UUID aparelhoId;

    @Column(name = "origem", nullable = false, length = 10)
    private String origem;

    @Column(name = "plataforma", nullable = false, length = 10)
    private String plataforma;

    // Hora do servidor ao receber (o relógio do celular não é confiável).
    @Column(name = "registrado_em", nullable = false)
    private OffsetDateTime registradoEm;

}
