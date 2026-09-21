-- Carga gerada automaticamente por tools/carga_pricetab.py a partir do PRICETAB.TXT

INSERT INTO produtos (codigo_barras, descricao, preco_centavos) VALUES ('7891000315507', 'BISCOITO RECHEADO CHOCOLATE 140G', 389) ON CONFLICT (codigo_barras) DO UPDATE SET descricao = EXCLUDED.descricao, preco_centavos = EXCLUDED.preco_centavos;
INSERT INTO produtos (codigo_barras, descricao, preco_centavos) VALUES ('7894900011517', 'REFRIGERANTE COCA COLA REFRIG 2L', 850) ON CONFLICT (codigo_barras) DO UPDATE SET descricao = EXCLUDED.descricao, preco_centavos = EXCLUDED.preco_centavos;
