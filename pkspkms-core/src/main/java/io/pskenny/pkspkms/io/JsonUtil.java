package io.pskenny.pkspkms.io;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.PksFileRepository;
import io.pskenny.pkspkms.repo.query.CompiledQuery;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class JsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void writePksFilesToJson(PksFileRepository repository, CompiledQuery query, OutputStream out) {
        writePksFilesToJson(repository, query, null, out);
    }

    public static void writePksFilesToJson(PksFileRepository repository, CompiledQuery query,
                                           Consumer<PksFile> transformer, OutputStream out) {
        try {
            JsonGenerator gen = MAPPER.getFactory().createGenerator(out);
            gen.writeStartObject();
            gen.writeFieldName("files");
            gen.writeStartArray();
            AtomicInteger count = new AtomicInteger(0);

            repository.searchRegular(query, pksFile -> {
                if (transformer != null) {
                    transformer.accept(pksFile);
                }
                try {
                    gen.writeObject(pksFile.getMutableProperties());
                    count.incrementAndGet();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            gen.writeEndArray();
            gen.writeNumberField("resultSize", count.get());
            gen.writeEndObject();
            gen.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize files to JSON", e);
        }
    }

    public static String mapToJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize map to JSON", e);
        }
    }
}
