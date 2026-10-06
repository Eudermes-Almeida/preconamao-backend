package br.com.preconamao.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// Arquivos recebidos das lojas (PRICETAB ou dados da API), fora do banco e compactados (regra 10
// do multi-loja). Esta implementação grava numa pasta local (DES); em produção a gaveta será o
// Storage privado do Supabase — o resto do código só conhece salvar/ler/apagar.
@ApplicationScoped
public class ArmazenamentoArquivos {

    @ConfigProperty(name = "armazenamento.pasta")
    String pasta;

    // Devolve o caminho relativo (o que vai para carga_pricetab.caminho_arquivo).
    public String salvar(Integer lojaId, String nome, byte[] conteudo) {
        String caminho = "loja-" + lojaId + "/" + nome + ".gz";
        Path destino = Path.of(pasta).resolve(caminho);
        try {
            Files.createDirectories(destino.getParent());
            ByteArrayOutputStream compactado = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compactado)) {
                gzip.write(conteudo);
            }
            Files.write(destino, compactado.toByteArray());
            return caminho;
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível guardar o arquivo " + caminho, e);
        }
    }

    public byte[] ler(String caminho) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(Path.of(pasta).resolve(caminho))))) {
            return gzip.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o arquivo " + caminho, e);
        }
    }

    public void apagar(String caminho) {
        try {
            Files.deleteIfExists(Path.of(pasta).resolve(caminho));
        } catch (IOException e) {
            Log.warnf("Não foi possível apagar o arquivo antigo %s: %s", caminho, e.getMessage());
        }
    }
}
