package br.com.preconamao.service;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Quando a coleta da API precisa ser COMPLETA (scripts/033): 1ª coleta e 1 vez por dia a partir
// das 3h no horário de Brasília (-03:00); no resto, incremental.
class ColetaIncrementalTest {

    private static OffsetDateTime brasilia(String dataHora) {
        return OffsetDateTime.parse(dataHora + "-03:00");
    }

    @Test
    void primeiraColetaECompleta() {
        assertTrue(ColetaApiService.precisaCompleta(null, null, brasilia("2026-10-08T10:00:00")));
        assertTrue(ColetaApiService.precisaCompleta(brasilia("2026-10-08T09:00:00"), null, brasilia("2026-10-08T10:00:00")));
    }

    @Test
    void depoisDaCompletaDeHojeEIncremental() {
        OffsetDateTime completa = brasilia("2026-10-08T03:01:00");
        assertFalse(ColetaApiService.precisaCompleta(completa, completa, brasilia("2026-10-08T10:00:00")));
        assertFalse(ColetaApiService.precisaCompleta(completa, completa, brasilia("2026-10-09T02:59:00")));
    }

    @Test
    void passouDas3hSemCompletaDoDia() {
        OffsetDateTime completa = brasilia("2026-10-08T03:01:00");
        assertTrue(ColetaApiService.precisaCompleta(completa, completa, brasilia("2026-10-09T03:00:00")));
        assertTrue(ColetaApiService.precisaCompleta(completa, completa, brasilia("2026-10-10T01:00:00")));
    }

    @Test
    void servidorEmUtcUsaOHorarioDeBrasilia() {
        // 05:30 UTC = 02:30 em Brasília: ainda não chegou a hora da completa do dia.
        OffsetDateTime completa = brasilia("2026-10-08T03:01:00");
        assertFalse(ColetaApiService.precisaCompleta(completa, completa, OffsetDateTime.parse("2026-10-09T05:30:00Z")));
        assertTrue(ColetaApiService.precisaCompleta(completa, completa, OffsetDateTime.parse("2026-10-09T06:30:00Z")));
    }
}
