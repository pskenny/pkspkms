/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of PKSPKMS.
 * Copyright (C) 2026 Paul Kenny
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.pskenny.pkspkms.repo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.query.CompiledQuery;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Streams query results as a JSON object out of the repository. Lives in
 * {@code repo} because its signature is repository-shaped — the {@code io}
 * utility layer must not import the repository (layering fix: the old
 * {@code io/JsonUtil} did).
 */
public final class PksJsonWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PksJsonWriter() {}

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
}
