-- Carga gerada automaticamente por tools/carga_pricetab.py a partir do PRICETAB.TXT

INSERT INTO produtos (codigo_barras, descricao, preco_centavos) VALUES ('7898065730085', 'PEDRA SANITARIA SANY LAVANDA', 250) ON CONFLICT (codigo_barras) DO UPDATE SET descricao = EXCLUDED.descricao, preco_centavos = EXCLUDED.preco_centavos;
INSERT INTO produtos (codigo_barras, descricao, preco_centavos) VALUES ('7898255671914', 'SABONACEO CREMOSO AUDAX FACILITA 300ML', 690) ON CONFLICT (codigo_barras) DO UPDATE SET descricao = EXCLUDED.descricao, preco_centavos = EXCLUDED.preco_centavos;
