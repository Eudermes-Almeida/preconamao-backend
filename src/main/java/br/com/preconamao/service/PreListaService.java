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

        Map<Long, PreListaCategoriaDTO> categorias = new LinkedHashMap<>();
        for (PreListaItemEntity item : itens) {
            PreListaCategoriaEntity categoria = item.getCategoria();
            categorias.computeIfAbsent(categoria.getId(), id -> PreListaCategoriaDTO.builder()
                            .id(id)
                            .nome(categoria.getNome())
                            .itens(new ArrayList<>())
                            .build())
                    .getItens()
                    .add(PreListaItemDTO.builder().id(item.getId()).nome(item.getNome()).build());
        }

        return new ArrayList<>(categorias.values());
    }
}
