package br.com.preconamao.service;

import br.com.preconamao.dto.PreListaCategoriaDTO;
import br.com.preconamao.dto.PreListaItemDTO;
import br.com.preconamao.entity.PreListaCategoriaEntity;
import br.com.preconamao.entity.PreListaItemEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PreListaService {

    @Inject
    EntityManager entityManager;

    // Catálogo completo (categorias com seus itens), já na ordem de exibição dos accordions.
    @Transactional
    public List<PreListaCategoriaDTO> listaCatalogo() {
        List<PreListaItemEntity> itens = entityManager.createQuery(
                        "SELECT i FROM PreListaItemEntity i JOIN FETCH i.categoria c "
                                + "ORDER BY c.ordem, i.ordem",
                        PreListaItemEntity.class)
                .getResultList();
        Map<Long, List<String>> falas = textosPorItem("SELECT item_id, fala FROM pre_lista_fala WHERE NOT marca ORDER BY item_id, fala");
        // Marca falada ("coca cola" -> Refrigerante): o app também a guarda no lembrete da linha.
        Map<Long, List<String>> marcas = textosPorItem("SELECT item_id, fala FROM pre_lista_fala WHERE marca ORDER BY item_id, fala");
        // Termo com curinga (LEITE % DESNATADO) não é um jeito de falar: fica de fora.
        Map<Long, List<String>> termos = textosPorItem(
                "SELECT item_id, lower(termo) FROM pre_lista_termo WHERE strpos(termo, '%') = 0 ORDER BY item_id, termo");

        Map<Long, PreListaCategoriaDTO> categorias = new LinkedHashMap<>();
        for (PreListaItemEntity item : itens) {
            PreListaCategoriaEntity categoria = item.getCategoria();
            categorias.computeIfAbsent(categoria.getId(), id -> PreListaCategoriaDTO.builder()
                            .id(id)
                            .nome(categoria.getNome())
                            .itens(new ArrayList<>())
                            .build())
                    .getItens()
                    .add(PreListaItemDTO.builder().id(item.getId()).nome(item.getNome())
                            .falas(falas.getOrDefault(item.getId(), List.of()))
                            .marcas(marcas.getOrDefault(item.getId(), List.of()))
                            .termos(termos.getOrDefault(item.getId(), List.of()))
                            .build());
        }

        return new ArrayList<>(categorias.values());
    }

    // (item_id, texto) -> textos de cada item, na ordem da consulta.
    @SuppressWarnings("unchecked")
    private Map<Long, List<String>> textosPorItem(String sql) {
        Map<Long, List<String>> porItem = new HashMap<>();
        for (Object[] linha : (List<Object[]>) entityManager.createNativeQuery(sql).getResultList()) {
            porItem.computeIfAbsent(((Number) linha[0]).longValue(), id -> new ArrayList<>()).add((String) linha[1]);
        }
        return porItem;
    }
}
