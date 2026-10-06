package br.com.preconamao.service;

import br.com.preconamao.dto.PreListaCategoriaDTO;
import br.com.preconamao.dto.PreListaItemDTO;
import br.com.preconamao.entity.LojaEntity;
import br.com.preconamao.entity.PreListaCategoriaEntity;
import br.com.preconamao.entity.PreListaItemEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class PreListaService {

    @Inject
    EntityManager entityManager;

    // Catálogo (categorias com seus itens), já na ordem de exibição dos accordions. A lista é a
    // mesma para todas as lojas; cada item diz se a loja da consulta tem algum produto ativo ligado
    // a ele (regra 3b do multi-loja: o app esconde o que a loja não tem).
    @Transactional
    @SuppressWarnings("unchecked")
    public List<PreListaCategoriaDTO> listaCatalogo(LojaEntity loja) {
        List<PreListaItemEntity> itens = entityManager.createQuery(
                        "SELECT i FROM PreListaItemEntity i JOIN FETCH i.categoria c ORDER BY c.ordem, i.ordem",
                        PreListaItemEntity.class)
                .getResultList();
        Set<Long> naLoja = ((List<Number>) entityManager.createNativeQuery(
                        "SELECT DISTINCT pre_lista_item_id FROM produtos WHERE loja_id = :loja AND ativo AND pre_lista_item_id IS NOT NULL")
                .setParameter("loja", loja.getId())
                .getResultList()).stream().map(Number::longValue).collect(Collectors.toSet());

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
                            .disponivel(naLoja.contains(item.getId())).build());
        }

        return new ArrayList<>(categorias.values());
    }
}
