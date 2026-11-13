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

package io.pskenny.pkspkms.repo.query;

import java.util.List;

/**
 * Flat clause list with Lucene classic semantics: default clauses are SHOULD,
 * AND promotes its adjacent clauses to MUST, -/NOT marks MUST_NOT.
 * Compile rules: MUSTs AND; SHOULDs OR (only when no MUSTs); MUST_NOTs excluded.
 */
public record Query(List<Clause> clauses) {

    public enum Occur { MUST, SHOULD, MUST_NOT }

    public record Clause(Occur occur, QueryNode node) {}
}
