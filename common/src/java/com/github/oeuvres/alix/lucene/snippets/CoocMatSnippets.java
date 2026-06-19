/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.Objects;
import java.util.function.IntConsumer;

import com.github.oeuvres.alix.lucene.snippets.SpanWalker.SnippetsConsumer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.util.CoocMat;
import com.github.oeuvres.alix.util.IntList;

/**
 * A {@link SnippetsConsumer} that fills a {@link CoocMat} with per-snippet co-occurrence counts, the
 * matrix-valued sibling of {@code TopCoocSnippets}.
 *
 * <h2>Counting model: per-snippet window, occurrence counts</h2>
 * <p>
 * One context is one snippet window. For each snippet at positions {@code [start, end)} the window
 * {@code [start - left, end + right)} is scanned through {@link TermRail#scanWindow}, which yields the
 * node occurrences in ascending position order. Each occurrence is counted, not merely its presence:
 * a node appearing {@code k} times in the window contributes {@code k}. Snippet windows are scanned
 * independently and are never unioned, so a node falling inside two overlapping windows is counted in
 * both — the price of strict snippet-level co-occurrence. After each window:
 * </p>
 * <ul>
 * <li>each node's diagonal cell is incremented by its occurrence count in the window — the diagonal is
 * therefore the occurrence marginal f(a) = &#931; n(a);</li>
 * <li>for each unordered pair the off-diagonal cells receive n(a)&#183;n(b), the number of co-occurring
 * occurrence pairs (see <i>Direction</i> for how the two cells are split);</li>
 * <li>{@link CoocMat#incTotal()} is called once — {@link CoocMat#total()} is therefore the number of
 * snippet windows, the sample size N.</li>
 * </ul>
 * <p>
 * Marginals, joints and N thus come out on one consistent occurrence-and-window basis. log-Dice and
 * PMI (with N from {@link CoocMat#total()}) are well defined on this footing; the G&#178;/log-likelihood
 * contingency interpretation is approximate, since occurrence products are not Bernoulli trial counts.
 * </p>
 *
 * <h2>Direction</h2>
 * <p>
 * When {@code directed} is {@code false} the matrix is symmetric and full: both {@code (a, b)} and
 * {@code (b, a)} receive n(a)&#183;n(b). When {@code directed} is {@code true} the matrix is
 * directional: {@code (a, b)} counts only the occurrence pairs in which <em>a precedes b</em> in the
 * text, and {@code (b, a)} the reverse, so the two cells sum to n(a)&#183;n(b) and the undirected
 * matrix is the directed one folded onto its transpose. Ascending-position scanning makes the callback
 * order the textual order, which is what lets the split be computed without reading position values.
 * The diagonal is the occurrence marginal in both modes, never a self-pair count.
 * </p>
 *
 * <h2>Node set and exclusions</h2>
 * <p>
 * The matrix is built over a fixed node set. Any term id outside that set maps to rank {@code -1} via
 * {@link CoocMat#rank(int)} and is ignored. Query pivots appear as rows and columns if and only if
 * their ids are in the node set; there is no pivot logic here.
 * </p>
 *
 * <h2>Preconditions and lifecycle</h2>
 * <p>
 * The {@link TermRail} and the node ids must share one term-id space, i.e. the same
 * {@code TermLexicon} and field. The matrix passed at construction is filled in place; this consumer
 * keeps only per-window scratch, all of it cleared per window, so one instance may be reused across
 * successive {@code walk} calls without a reset. Not thread-safe.
 * </p>
 */
public final class CoocMatSnippets implements SnippetsConsumer, IntConsumer
{
    /** Whether co-occurrences are recorded directionally ({@code (a, b)} = a precedes b). */
    private final boolean directed;

    /** Number of context positions read to the left of each snippet. */
    private final int left;

    /** Target matrix, filled in place. */
    private final CoocMat mat;

    /** Forward positional rail for the matrix's field. */
    private final TermRail rail;

    /** Number of context positions read to the right of each snippet. */
    private final int right;

    /** Per-window occurrence count, one per rank; reset to zero after each window. */
    private final int[] seenCount;

    /** Distinct ranks seen in the current window, in first-seen order. */
    private final IntList touched = new IntList();

    /**
     * Constructs a co-occurrence-matrix listener over a fixed node set.
     *
     * @param mat matrix to fill; its node ids must live in {@code rail}'s term-id space.
     * @param rail forward positional rail for the same field.
     * @param left context width on the left of each snippet, in positions; {@code >= 0}.
     * @param right context width on the right of each snippet, in positions; {@code >= 0}.
     * @param directed {@code true} to record direction ({@code (a, b)} counts a before b),
     * {@code false} for a symmetric full matrix.
     * @throws NullPointerException if {@code mat} or {@code rail} is {@code null}.
     * @throws IllegalArgumentException if {@code left} or {@code right} is negative.
     */
    public CoocMatSnippets(
        final CoocMat mat,
        final TermRail rail,
        final int left,
        final int right,
        final boolean directed
    ) {
        this.mat = Objects.requireNonNull(mat, "mat");
        this.rail = Objects.requireNonNull(rail, "rail");
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException(
                "left and right must be >= 0; got left=" + left + ", right=" + right);
        }
        this.left = left;
        this.right = right;
        this.directed = directed;
        this.seenCount = new int[mat.length()];
    }

    /**
     * Rail sink: records one node occurrence in the current window. Adds this occurrence's
     * co-occurrence with every distinct node already seen in the window — to one cell when
     * {@code directed}, to both when symmetric — then increments this node's occurrence count. Public
     * only to satisfy {@link IntConsumer}; not meant to be called directly.
     *
     * @param termId a term id read from the rail.
     */
    @Override
    public void accept(final int termId)
    {
        final int rank = mat.rank(termId);
        if (rank < 0) {
            return;
        }
        final int distinct = touched.size();
        final int[] ranks = touched.data();
        for (int i = 0; i < distinct; i++) {
            final int seen = ranks[i];
            if (seen == rank) {
                continue;
            }
            final int count = seenCount[seen];
            mat.incByRank(seen, rank, count);
            if (!directed) {
                mat.incByRank(rank, seen, count);
            }
        }
        if (seenCount[rank] == 0) {
            touched.push(rank);
        }
        seenCount[rank]++;
    }

    /**
     * Accumulates one document's snippets into the matrix, treating each snippet window as a separate
     * context. For each snippet the window is scanned in position order, off-diagonal pair cells are
     * filled as occurrences arrive, then each present node's diagonal is incremented by its occurrence
     * count and the window is counted toward {@link CoocMat#total()}.
     *
     * @param docId global Lucene document id.
     * @param snippets finished snippets for {@code docId}; read only within this call.
     * @throws IOException never thrown here, declared by the interface.
     */
    @Override
    public void docSnippets(
        final int docId,
        final Snippets snippets
    )
        throws IOException
    {
        final int snipCount = snippets.count();
        for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
            final int start = snippets.snipStartPosition(snipOrd);
            final int end = snippets.snipEndPosition(snipOrd);

            touched.clear();
            rail.scanWindow(docId, start - left, end + right, this);

            final int distinct = touched.size();
            final int[] ranks = touched.data();
            for (int i = 0; i < distinct; i++) {
                final int rank = ranks[i];
                mat.incByRank(rank, rank, seenCount[rank]);
                seenCount[rank] = 0;
            }
            mat.incTotal();
        }
    }
}
