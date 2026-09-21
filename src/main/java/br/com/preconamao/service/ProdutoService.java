package br.com.preconamao.service;

import br.com.preconamao.dto.ProdutoDTO;
import br.com.preconamao.entity.ProdutoEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import java.util.Optional;

@ApplicationScoped
public class ProdutoService {

    @Inject
    EntityManager entityManager;

    @Transactional
    public Optional<ProdutoDTO> buscaPorCodigoBarras(String codigoBarras) {
        String codigoTratado = codigoBarras.trim();

        try {
            ProdutoEntity produtoEntity = entityManager.createQuery(
                            "SELECT p FROM ProdutoEntity p WHERE p.codigoBarras = :codigoBarras",
                            ProdutoEntity.class)
                    .setParameter("codigoBarras", codigoTratado)
                    .getSingleResult();

            return Optional.of(mapToDTO(produtoEntity));
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    private ProdutoDTO mapToDTO(ProdutoEntity entity) {
        return ProdutoDTO.builder()
                .codigoBarras(entity.getCodigoBarras())
                .descricao(entity.getDescricao())
                .precoCentavos(entity.getPrecoCentavos())
                .build();
    }
}
