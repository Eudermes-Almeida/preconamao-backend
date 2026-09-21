-- Tabela de produtos: espelha os campos processados do PRICETAB.TXT da Gertec
-- (codigo_barras, descricao, preco). Documentação/histórico manual — em dev o
-- schema é criado automaticamente pelo Hibernate (quarkus.hibernate-orm.schema-management.strategy=update).

CREATE TABLE IF NOT EXISTS produtos (
    id              BIGSERIAL PRIMARY KEY,
    codigo_barras   VARCHAR(13) NOT NULL,
    descricao       VARCHAR(40) NOT NULL,
    preco_centavos  INTEGER NOT NULL,
    CONSTRAINT uk_produtos_codigo_barras UNIQUE (codigo_barras)
);
