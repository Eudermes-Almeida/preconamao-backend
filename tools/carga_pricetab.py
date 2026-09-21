"""
Converte um arquivo PRICETAB.TXT (formato Gertec) em um script SQL de carga,
seguindo a mesma convenção de scripts numerados usada no projeto RAIO_X_UNIDADE
(modelagem_dados_postgres/scripts/NNN_carga_*_local.sql).

Formato de cada linha, conforme o documento de contexto do projeto:
CODIGO_DE_BARRAS(13)|DESCRICAO(40, padded)|PRECO(10, zero-padded, sem virgula)||

Uso:
    python tools/carga_pricetab.py caminho/para/PRICETAB.TXT
"""
import re
import sys
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent.parent / "modelagem_dados_postgres" / "scripts"


def proximo_numero_sequencial() -> int:
    numeros = []
    for arquivo in SCRIPTS_DIR.glob("*.sql"):
        match = re.match(r"^(\d+)_", arquivo.name)
        if match:
            numeros.append(int(match.group(1)))
    return max(numeros, default=0) + 1


def parseia_linha(linha: str, numero_linha: int) -> tuple[str, str, int] | None:
    linha_tratada = linha.rstrip("\r\n")
    if not linha_tratada.strip():
        return None

    if not linha_tratada.endswith("||"):
        print(f"[aviso] linha {numero_linha} não termina com '||', ignorada: {linha_tratada!r}", file=sys.stderr)
        return None

    corpo = linha_tratada[:-2]
    partes = corpo.split("|")
    if len(partes) != 3:
        print(f"[aviso] linha {numero_linha} não tem 3 campos separados por '|', ignorada: {linha_tratada!r}", file=sys.stderr)
        return None

    codigo_barras, descricao, preco_str = partes
    codigo_barras = codigo_barras.strip()
    descricao = descricao.rstrip()
    preco_str = preco_str.strip()

    if len(codigo_barras) != 13:
        print(f"[aviso] linha {numero_linha}: código de barras com {len(codigo_barras)} caracteres (esperado 13): {codigo_barras!r}", file=sys.stderr)

    try:
        preco_centavos = int(preco_str)
    except ValueError:
        print(f"[aviso] linha {numero_linha}: preço inválido, ignorada: {preco_str!r}", file=sys.stderr)
        return None

    return codigo_barras, descricao, preco_centavos


def escapa_sql(texto: str) -> str:
    return texto.replace("'", "''")


def gera_sql(produtos: list[tuple[str, str, int]]) -> str:
    linhas = [
        "-- Carga gerada automaticamente por tools/carga_pricetab.py a partir do PRICETAB.TXT",
        "",
    ]
    for codigo_barras, descricao, preco_centavos in produtos:
        linhas.append(
            "INSERT INTO produtos (codigo_barras, descricao, preco_centavos) VALUES "
            f"('{escapa_sql(codigo_barras)}', '{escapa_sql(descricao)}', {preco_centavos}) "
            "ON CONFLICT (codigo_barras) DO UPDATE SET "
            "descricao = EXCLUDED.descricao, preco_centavos = EXCLUDED.preco_centavos;"
        )
    return "\n".join(linhas) + "\n"


def main() -> None:
    if len(sys.argv) != 2:
        print("Uso: python tools/carga_pricetab.py caminho/para/PRICETAB.TXT", file=sys.stderr)
        sys.exit(1)

    caminho_entrada = Path(sys.argv[1])
    if not caminho_entrada.is_file():
        print(f"Arquivo não encontrado: {caminho_entrada}", file=sys.stderr)
        sys.exit(1)

    produtos = []
    with caminho_entrada.open(encoding="latin-1") as arquivo:
        for numero_linha, linha in enumerate(arquivo, start=1):
            resultado = parseia_linha(linha, numero_linha)
            if resultado:
                produtos.append(resultado)

    if not produtos:
        print("Nenhum produto válido encontrado no arquivo.", file=sys.stderr)
        sys.exit(1)

    SCRIPTS_DIR.mkdir(parents=True, exist_ok=True)
    numero = proximo_numero_sequencial()
    caminho_saida = SCRIPTS_DIR / f"{numero:03d}_carga_pricetab_local.sql"
    caminho_saida.write_text(gera_sql(produtos), encoding="utf-8")

    print(f"{len(produtos)} produto(s) processado(s).")
    print(f"Script gerado em: {caminho_saida}")


if __name__ == "__main__":
    main()
