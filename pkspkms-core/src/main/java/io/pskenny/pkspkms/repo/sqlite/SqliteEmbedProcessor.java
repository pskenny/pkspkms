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

package io.pskenny.pkspkms.repo.sqlite;

import io.pskenny.pkspkms.luabase.LuaBaseProcessor;
import io.pskenny.pkspkms.luabase.NaiveBaseToLuaBaseConverter;
import io.pskenny.pkspkms.io.YamlParser;
import io.pskenny.pkspkms.repo.PksFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.*;
import java.util.List;
import java.util.Map;

/**
 * Processes embed records in the database, generating rendered content
 * for base and luabase embed types.
 *
 * Owns its own JDK connection: the batch render pass writes while the
 * repository's shared connection stays free (single-writer DB, WAL readers).
 *
 * Slimmed in the P3 sweep: no computed_props construction, no wikilink
 * resolver connection — generated_content is the only produced column.
 */
public class SqliteEmbedProcessor {
    private static final Logger logger = LoggerFactory.getLogger(SqliteEmbedProcessor.class);
    private final String dbUrl;
    private final LuaBaseProcessor processor;
    private final NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter;
    private final PksFileRepository repository;

    public void processAll() throws SQLException, IOException {
        String selectSql = "SELECT id, original_match, type FROM EMBEDS";
        String updateSql = "UPDATE EMBEDS SET generated_content = ? WHERE id = ?";
        long startNs = System.nanoTime();

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            SQLitePragmas.apply(conn);

            conn.setAutoCommit(false);
            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql);
                 PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                ResultSet rs = selectStmt.executeQuery();
                int generated = 0;
                int errors = 0;

                // Single corpus load: one JSON parse per file for the whole
                // render pass — embeds evaluate in-process against it
                List<io.pskenny.pkspkms.io.PksFile> corpus = repository.loadCorpus();

                while (rs.next()) {
                    int id = rs.getInt("id");
                    String text = rs.getString("original_match");
                    String type = rs.getString("type");
                    String generatedText = "";
                    try {
                        generatedText = renderWithWatchdog(type, text, corpus);
                    } catch (Throwable ex) {
                        // Throwable, not Exception: a Lua runtime StackOverflow
                        // (Error) must degrade this embed, not kill the batch
                        // (BUGS.md M2)
                        logger.error("Couldn't generate Markdown text from {}: {}", type,
                                io.pskenny.pkspkms.io.LogUtil.oneLine(text), ex);
                        errors++;
                    }
                    if ("ERROR".equals(generatedText)) {
                        errors++;
                    } else if (!generatedText.isEmpty()) {
                        generated++;
                    }

                    updateStmt.setString(1, generatedText);
                    updateStmt.setInt(2, id);
                    updateStmt.executeUpdate();
                }

                conn.commit();
                logger.info("Generated {} embeds in {}ms (errors: {})",
                        generated, (System.nanoTime() - startNs) / 1_000_000, errors);

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // DoS backstop (BUGS.md M2): each embed renders on a small daemon pool
    // with a per-embed wall-clock budget. A note-controlled Lua filter/order
    // column can loop forever inside luaj (no instruction hooks on compiled
    // chunks in luaj 3.0.1); the watchdog marks the embed "ERROR" and lets the
    // mount continue. The stalled worker thread leaks until process exit
    // (daemon, bounded pool) — documented residual in docs/core/Security.md.
    private static final java.util.concurrent.ExecutorService EMBED_WORKERS =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2)),
                    r -> {
                        Thread t = new Thread(r, "embed-render");
                        t.setDaemon(true);
                        return t;
                    });

    private static final long DEFAULT_EMBED_TIMEOUT_MS =
            Long.getLong("pkspkms.embed.timeoutMs", 15_000L);

    private final long embedTimeoutMs;

    public SqliteEmbedProcessor(String dbUrl, LuaBaseProcessor processor,
                         NaiveBaseToLuaBaseConverter converter,
                         PksFileRepository repository) {
        this(dbUrl, processor, converter, repository, DEFAULT_EMBED_TIMEOUT_MS);
    }

    // Test seam: a tiny watchdog timeout so a hostile embed fails bounded
    // instead of burning the suite for the full default budget.
    public SqliteEmbedProcessor(String dbUrl, LuaBaseProcessor processor,
                         NaiveBaseToLuaBaseConverter converter,
                         PksFileRepository repository,
                         long embedTimeoutMs) {
        this.dbUrl = dbUrl;
        this.processor = processor;
        this.naiveBaseToLuaBaseConverter = converter;
        this.repository = repository;
        this.embedTimeoutMs = embedTimeoutMs;
    }

    private String renderWithWatchdog(String type, String text, List<io.pskenny.pkspkms.io.PksFile> corpus)
            throws InterruptedException {
        java.util.concurrent.Future<String> pending =
                EMBED_WORKERS.submit(() -> generateEmbedContent(type, text, corpus));
        try {
            return pending.get(embedTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // cancel(true) cannot stop a Lua bytecode loop, but releases the
            // slot's future; the worker thread itself is left burning and is
            // replaced by its successor — bounded leak, embedded known risk
            pending.cancel(true);
            logger.warn("Embed render exceeded {}ms, marked ERROR: {}", embedTimeoutMs,
                    io.pskenny.pkspkms.io.LogUtil.oneLine(text));
            return "ERROR";
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.error("Embed render failed: {}", io.pskenny.pkspkms.io.LogUtil.oneLine(text), cause);
            return "ERROR";
        }
    }

    private String generateEmbedContent(String type, String text, List<io.pskenny.pkspkms.io.PksFile> corpus) {
        if ("luabase".equalsIgnoreCase(type)) {
            return processLuaBaseText(text, corpus);
        }
        if ("base".equalsIgnoreCase(type)) {
            return processBaseMap(naiveBaseToLuaBaseConverter.convertToMap(text), corpus);
        }
        return "";
    }

    private String processLuaBaseText(String text, List<io.pskenny.pkspkms.io.PksFile> corpus) {
        try {
            Map<String, Object> spec = new YamlParser().parse(text);
            return processor.processOverCorpus(spec, corpus);
        } catch (Exception e) {
            logger.error("Couldn't process Base text: {}", io.pskenny.pkspkms.io.LogUtil.oneLine(text), e);
        }
        return "ERROR";
    }

    private String processBaseMap(Map<String, Object> spec, List<io.pskenny.pkspkms.io.PksFile> corpus) {
        try {
            return processor.processOverCorpus(spec, corpus);
        } catch (Exception e) {
            logger.error("Couldn't process Base spec: {}", io.pskenny.pkspkms.io.LogUtil.oneLine(String.valueOf(spec)));
        }
        return "ERROR";
    }
}
