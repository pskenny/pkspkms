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

package io.pskenny.pkspkms.io.processor.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task (checkbox) extraction from note bodies: open {@code - [ ]} lines feed
 * the {@code tasks} property, {@code [x]/[X]} lines feed {@code tasksDone}.
 * Fence-masked bodies (the standard BlockMasker pass) must not leak demo
 * checkboxes into the index.
 */
public class TaskReaderTest {

    private final TaskReader reader = new TaskReader();

    @Test
    void openTasksCollectedAndDoneIgnored() {
        var lists = reader.read("""
                - [ ] buy milk
                - [x] already done
                - [X] done capitalized
                """);
        assertEquals(List.of("buy milk"), lists.open());
        assertEquals(List.of("already done", "done capitalized"), lists.done());
    }

    @Test
    void listMarkersDashStarPlus() {
        var lists = reader.read("""
                * [ ] star task
                + [ ] plus task
                - [ ] dash task
                """);
        assertEquals(List.of("star task", "plus task", "dash task"), lists.open());
        assertTrue(lists.done().isEmpty());
    }

    @Test
    void indentedListItems() {
        var lists = reader.read("""
                    - [ ] one space deep
                  * [ ] deeper
                """);
        assertEquals(List.of("one space deep", "deeper"), lists.open());
    }

    @Test
    void nonTaskLinesIgnored() {
        var lists = reader.read("""
                - [ ] real task
                Just a mention of [x] in prose.
                - [X without space
                - [ ] trailing spaces only
                [ ] no list marker
                > - [ ] quoted task is out of scope
                1. [ ] ordered is out of scope
                """);
        assertEquals(List.of("real task", "trailing spaces only"), lists.open());
    }

    @Test
    void duplicatesCollapse() {
        var lists = reader.read("""
                - [ ] same thing
                * [ ] same thing
                - [ ] same thing
                """);
        assertEquals(List.of("same thing"), lists.open());
    }

    @Test
    void capLimitsDistinctTasks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            sb.append("- [ ] task ").append(i).append('\n');
        }
        var lists = reader.read(sb.toString());
        assertEquals(200, lists.open().size(), "capped at 200");
    }

    @Test
    void fencedDemoCheckboxesDoNotExtract() {
        // Composition contract with the loader: BlockMasker blanks fence
        // interiors but keeps every line, so TaskReader sees no task lines
        String masked = BlockMasker.maskFencedBlocks("""
                ```markdown
                - [ ] demo task inside a fence
                ```
                """);
        var lists = reader.read(masked);
        assertTrue(lists.open().isEmpty(), "fenced body must not leak tasks");
        assertTrue(lists.done().isEmpty());
    }

    @Test
    void nullAndEmptySafe() {
        var empty = reader.read(null);
        assertTrue(empty.open().isEmpty());
        assertTrue(empty.done().isEmpty());
    }
}
