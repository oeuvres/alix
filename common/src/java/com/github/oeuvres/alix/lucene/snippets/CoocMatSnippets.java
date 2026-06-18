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
import java.util.BitSet;
import java.util.Objects;
import java.util.function.IntConsumer;

import com.github.oeuvres.alix.lucene.snippets.SpanWalker.SnippetsConsumer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.util.CoocMat;
import com.github.oeuvres.alix.util.IntList;

/**
 * A {@link SnippetsConsumer} that fills a {@link CoocMat} with document-level co-occurrence
 * counts, the matrix-valued sibling of {@code TopCoocSnippets}.
 *
 * <h2>Counting model: per-document presence</h2>
 * <p>
 * One context is one document. For each document the walker delivers, the union of every snippet's
 * window {@code [max(0, start - left), end + right)} is marked in a reusable bitset, the rail is
 * asked for the term ids at those positions, and each id is mapped to a matrix rank. A node counts
 * at most once per document regardless of how many times it occurs in the window, so the matrix
 * records document presence, not occurrence frequency. After the scan:
 * </p>
 * <ul>
 * <li>each present node's diagonal cell is incremented once — the diagonal is therefore document
 * frequency, f(a);</li>
 * <li>each unordered pair of present nodes is incremented once, canonicalised to {@code row < col}
 * so only the upper triangle is written — f(a,b);</li>
 * <li>{@link CoocMat#incTotal()} is called once — {@link CoocMat#total()} is therefore the number
 * of focus documents, the sample size N.</li>
 * </ul>
 * <p>
 * A document with no node present (its windowed content is entirely non-nodes) still counts toward
 * N: it is a context in which no node appeared, and dropping it would bias the marginal rates the
 * scorers divide by. This is the contingency-table footing log-Dice, PMI, NPMI, LMI and
 * log-likelihood all assume; f(a), f(b), f(a,b) and N come out on one consistent document basis.
 * </p>
 *
 * <h2>Reading the matrix</h2>
 * <p>
 * Only the upper triangle is filled. A reader wanting the count for an unordered pair must
 * canonicalise the same way, e.g. {@code mat.count(min(a, b), max(a, b))}; the lower triangle is
 * left at zero.
 * </p>
 *
 * <h2>Node set and exclusions</h2>
 * <p>
 * The matrix is built over a fixed node set (typically the top-K cooccurrents of a prior pass). Any
 * term id outside that set maps to rank {@code -1} via {@link CoocMat#rank(int)} and is ignored, so
 * query pivots — which are not among their own cooccurrents — are excluded for free, with no pivot
 * logic here. To make pivots appear as rows and columns, include their ids in the node set.
 * </p>
 *
 * <h2>Preconditions and lifecycle</h2>
 * <p>
 * The {@link TermRail} and the node ids must share one term-id space, i.e. the same
 * {@code TermLexicon} and field. The matrix passed at construction is filled in place; this
 * consumer keeps only per-document scratch, all of it cleared per document, so one instance may be
 * reused across successive {@code walk} calls without a reset. Not thread-safe.
 * </p>
 */
public final class CoocMatSnippets implements SnippetsConsumer, IntConsumer
{
    /** Target matrix, filled in place. */
    private final CoocMat mat;

    /** Per-document presence flags, one per rank; {@code true} while a node is seen in the document. */
    private final boolean[] present;

    /** Distinct ranks present in the current document, in first-seen order. */
    private final IntList presentRanks = new IntList();

    /** Number of context positions read to the left of each snippet. */
    private final int left;

    /** Forward positional rail for the matrix's field. */
    private final TermRail rail;

    /** Number of context positions read to the right of each snippet. */
    private final int right;

    /** Accumulated window positions across all snippets of the current document. */
    private final BitSet windowMask = new BitSet();

    /**
     * Constructs a co-occurrence-matrix listener over a fixed node set.
     *
     * @param mat matrix to fill; its node ids must live in {@code rail}'s term-id space.
     * @param rail forward positional rail for the same field.
     * @param left context width on the left of each snippet, in positions; {@code >= 0}.
     * @param right context width on the right of each snippet, in positions; {@code >= 0}.
     * @throws NullPointerException if {@code mat} or {@code rail} is {@code null}.
     * @throws IllegalArgumentException if {@code left} or {@code right} is negative.
     */
    public CoocMatSnippets(
        final CoocMat mat,
        final TermRail rail,
        final int left,
        final int right
    ) {
        this.mat = Objects.requireNonNull(mat, "mat");
        this.rail = Objects.requireNonNull(rail, "rail");
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException(
                "left and right must be >= 0; got left=" + left + ", right=" + right);
        }
        this.left = left;
        this.right = right;
        this.present = new boolean[mat.length()];
    }

    /**
     * Rail sink: records a scanned term id as present in the current document if it is a node, at
     * most once. Public only to satisfy {@link IntConsumer}; not meant to be called directly.
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
        if (!present[rank]) {
            present[rank] = true;
            presentRanks.push(rank);
        }
    }

    /**
     * Accumulates one document's co-occurrences into the matrix. Unions every snippet's window,
     * resolves the windowed positions to distinct present nodes, increments their diagonal cells and
     * the upper-triangle pair cells, and counts the document toward {@link CoocMat#total()}.
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
        windowMask.clear();
        final int snipCount = snippets.count();
        for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
            final int start = snippets.snipStartPosition(snipOrd);
            final int end = snippets.snipEndPosition(snipOrd);
            windowMask.set(Math.max(0, start - left), end + right);
        }

        presentRanks.clear();
        rail.scanPositions(docId, windowMask, this);

        final int nodeCount = presentRanks.size();
        final int[] ranks = presentRanks.data();
        for (int i = 0; i < nodeCount; i++) {
            final int rowRank = ranks[i];
            present[rowRank] = false;
            mat.incByRank(rowRank, rowRank);
            for (int j = i + 1; j < nodeCount; j++) {
                final int colRank = ranks[j];
                if (rowRank < colRank) {
                    mat.incByRank(rowRank, colRank);
                }
                else {
                    mat.incByRank(colRank, rowRank);
                }
            }
        }
        mat.incTotal();
    }
}
