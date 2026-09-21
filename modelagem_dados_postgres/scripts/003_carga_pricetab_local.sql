-- Carga gerada automaticamente por tools/carga_pricetab.py a partir do PRICETAB.TXT

INSERT INTO produtos (codigo_barras, descricao, preco_centavos) VALUES ('7891024134610', 'CREME DENTAL COLGATE 180G CALCI-PROTECT', 548) ON CONFLICT (codigo_barras) DO UPDATE SET descricao = EXCLUDED.descricao, preco_centavos = EXCLUDED.preco_centavos;
INSERT INTO produtos (codigo_barras, descricao, preco_centavos) VALUES ('7898255671617', 'MULTIUSO VEJA 500ML', 625) ON CONFLICT (codigo_barras) DO UPDATE SET descricao = EXCLUDED.descricao, preco_centavos = EXCLUDED.preco_centavos;
INSERT INTO produtos (codigo_barras, descricao, preco_centavos) VALUES ('7898005490529', 'ALCOOL 70 ARCHOTE 1L', 835) ON CONFLICT (codigo_barras) DO UPDATE SET descricao = EXCLUDED.descricao, preco_centavos = EXCLUDED.preco_centavos;
