/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
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
package com.github.oeuvres.alix.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Compact automaton over token-id sequences (int[]) for multi-word expression matching.
 *
 * <p>Lifecycle mirrors {@link CharsDic}: the object is created mutable, populated
 * incrementally via {@link #add(int[], int)}, then frozen once via {@link #freeze(boolean)}.
 * Runtime methods ({@link #step}, {@link #accept}, {@link #maxLen}) require the frozen state;
 * {@link #add} requires the mutable state.</p>
 *
 * <p>Accepting states may still have outgoing arcs (maximal munch: LEAF and BRANCH+LEAF).</p>
 */
public final class IntAutomaton
{
    // ---- Mutable trie state (null after freeze) ------------------------------

    private Node trieRoot;
    private int  buildMaxLen;

    // ---- Frozen packed state (null before freeze) ----------------------------

    /** Packed state -> outgoing arc range: arcs for state s are label/target[offset[s]..offset[s]+count[s]). */
    private int[] offset;
    private int[] count;

    /** Packed arcs, sorted by label within each state's range. */
    private int[] label;
    private int[] target;

    /** Accept id per state, or -1 for non-accepting. */
    private int[] accept;

    /** Maximum pattern length seen at build time. */
    private int maxLen;

    // ---- Constructor ---------------------------------------------------------

    /** Constructs an empty, mutable automaton ready for {@link #add} calls. */
    public IntAutomaton()
    {
        trieRoot     = new Node();
        buildMaxLen  = 0;
    }

    // ---- Mutable API ---------------------------------------------------------

    /**
     * Adds a pattern (token-id sequence) with its accept/output id.
     * If the same sequence is added more than once, the last {@code outputId} wins.
     *
     * @param tokenIds token-id sequence buffer
     * @param len      number of valid entries in {@code tokenIds}
     * @param outputId value returned by {@link #accept} when this pattern is matched
     * @throws IllegalStateException if {@link #freeze} has already been called
     */
    public void add(final int[] tokenIds, final int len, final int outputId)
    {
        if (trieRoot == null) throw new IllegalStateException("frozen");
        if (tokenIds == null || len < 1) return;

        Node n = trieRoot;
        for (int i = 0; i < len; i++) n = n.getOrAdd(tokenIds[i]);
        n.acceptId = outputId;

        if (len > buildMaxLen) buildMaxLen = len;
    }

    /**
     * Packs the trie into primitive arrays and makes the automaton immutable.
     * Calling {@link #add} after {@code freeze} throws {@link IllegalStateException}.
     * Calling {@code freeze} more than once is a no-op.
     *
     * @param minimize if true, merges equivalent suffix states (DAFSA minimization)
     */
    public void freeze(final boolean minimize)
    {
        if (trieRoot == null) return; // already frozen
        final Node canonRoot = minimize ? minimizeToDAFSA(trieRoot) : prepareTrie(trieRoot);
        pack(canonRoot, buildMaxLen);
        trieRoot = null; // release trie nodes for GC
    }

    // ---- Runtime API ---------------------------------------------------------

    /** Root state; pass as the initial state to the first {@link #step} call. */
    public int root()
    {
        checkFrozen();
        return 0;
    }

    /**
     * Transition function.
     *
     * @param state   current state
     * @param tokenId arc label (token id from {@link CharsDic})
     * @return next state, or -1 if no transition exists
     */
    public int step(final int state, final int tokenId)
    {
        checkFrozen();
        if (state < 0) return -1;
        final int n = count[state];
        if (n == 0) return -1;
        final int base = offset[state];

        if (n <= 8) {
            for (int i = 0; i < n; i++) {
                if (label[base + i] == tokenId) return target[base + i];
            }
            return -1;
        }

        int lo = base, hi = base + n - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final int v   = label[mid];
            if      (v < tokenId) lo = mid + 1;
            else if (v > tokenId) hi = mid - 1;
            else return target[mid];
        }
        return -1;
    }

    /**
     * Returns the accept id for {@code state}, or -1 if non-accepting.
     * For {@link MweLexicon}, the accept id is the {@link CharsDic} ordinal of the canonical form.
     */
    public int accept(final int state)
    {
        checkFrozen();
        if (state < 0) return -1;
        return accept[state];
    }

    /** Upper bound on pattern length in tokens; use to size the filter's lookahead deque. */
    public int maxLen()
    {
        checkFrozen();
        return maxLen;
    }

    // ---- Private: trie node --------------------------------------------------

    private static final class Node
    {
        int    acceptId = -1;
        int[]  labels   = new int[4];
        Node[] targets  = new Node[4];
        int    deg      = 0;
        int    hash     = 0;

        Node getOrAdd(final int lab)
        {
            for (int i = 0; i < deg; i++) {
                if (labels[i] == lab) return targets[i];
            }
            final Node n = new Node();
            if (deg == labels.length) {
                labels  = Arrays.copyOf(labels,  deg * 2);
                targets = Arrays.copyOf(targets, deg * 2);
            }
            labels[deg]  = lab;
            targets[deg] = n;
            deg++;
            return n;
        }

        void sortArcsByLabel()
        {
            quicksort(labels, targets, 0, deg - 1);
        }

        void freezeAndHash()
        {
            int h = 1_000_003 ^ acceptId;
            for (int i = 0; i < deg; i++) {
                h = 31 * h + labels[i];
                h = 31 * h + targets[i].hash;
            }
            hash = h;
        }

        @Override public int hashCode() { return hash; }

        @Override
        public boolean equals(final Object o)
        {
            if (this == o) return true;
            if (!(o instanceof Node other)) return false;
            if (acceptId != other.acceptId || deg != other.deg) return false;
            for (int i = 0; i < deg; i++) {
                if (labels[i]  != other.labels[i])  return false;
                if (targets[i] != other.targets[i]) return false; // pointer equality: children already canonical
            }
            return true;
        }
    }

    // ---- Private: trie preparation and minimization -------------------------

    private static Node prepareTrie(final Node root)
    {
        for (final Node n : postOrder(root)) {
            if (n.deg > 1) n.sortArcsByLabel();
            n.freezeAndHash();
        }
        return root;
    }

    private static Node minimizeToDAFSA(final Node root)
    {
        final List<Node> post = postOrder(root);
        for (final Node n : post) {
            if (n.deg > 1) n.sortArcsByLabel();
        }

        final HashMap<Node, Node> reg = new HashMap<>(post.size() * 2);
        for (final Node n : post) {
            for (int i = 0; i < n.deg; i++) {
                final Node canon = reg.get(n.targets[i]);
                if (canon != null) n.targets[i] = canon;
            }
            n.freezeAndHash();
            reg.putIfAbsent(n, n);
        }

        final Node canonRoot = reg.get(root);
        return canonRoot != null ? canonRoot : root;
    }

    // ---- Private: packing ----------------------------------------------------

    private void pack(final Node root, final int builtMaxLen)
    {
        final IdentityHashMap<Node, Integer> id = new IdentityHashMap<>();
        final ArrayDeque<Node> q = new ArrayDeque<>();
        final ArrayList<Node> nodes = new ArrayList<>();

        id.put(root, 0);
        q.add(root);
        nodes.add(root);
        int arcTotal = 0;

        while (!q.isEmpty()) {
            final Node n = q.removeFirst();
            arcTotal += n.deg;
            for (int i = 0; i < n.deg; i++) {
                final Node t = n.targets[i];
                if (!id.containsKey(t)) {
                    final int nid = nodes.size();
                    id.put(t, nid);
                    nodes.add(t);
                    q.addLast(t);
                }
            }
        }

        final int nStates = nodes.size();
        this.offset = new int[nStates];
        this.count  = new int[nStates];
        this.accept = new int[nStates];
        this.label  = new int[arcTotal];
        this.target = new int[arcTotal];
        this.maxLen = builtMaxLen;

        int p = 0;
        for (int s = 0; s < nStates; s++) {
            final Node n = nodes.get(s);
            accept[s] = n.acceptId;
            offset[s] = p;
            count[s]  = n.deg;
            for (int i = 0; i < n.deg; i++) {
                label[p]  = n.labels[i];
                target[p] = id.get(n.targets[i]);
                p++;
            }
        }
    }

    // ---- Private: utilities --------------------------------------------------

    private void checkFrozen()
    {
        if (trieRoot != null) throw new IllegalStateException("not frozen");
    }

    private static List<Node> postOrder(final Node root)
    {
        final IdentityHashMap<Node, Boolean> seen = new IdentityHashMap<>();
        final ArrayList<Node> out = new ArrayList<>();

        final class Frame { Node n; int i; Frame(Node n) { this.n = n; } }
        final ArrayDeque<Frame> st = new ArrayDeque<>();
        st.push(new Frame(root));
        seen.put(root, Boolean.TRUE);

        while (!st.isEmpty()) {
            final Frame f = st.peek();
            if (f.i < f.n.deg) {
                final Node child = f.n.targets[f.i++];
                if (seen.put(child, Boolean.TRUE) == null) st.push(new Frame(child));
            } else {
                st.pop();
                out.add(f.n);
            }
        }
        return out;
    }

    private static void quicksort(final int[] a, final Node[] b, int lo, int hi)
    {
        while (lo < hi) {
            int i = lo, j = hi;
            final int pivot = a[(lo + hi) >>> 1];
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    final int  ta = a[i]; a[i] = a[j]; a[j] = ta;
                    final Node tb = b[i]; b[i] = b[j]; b[j] = tb;
                    i++; j--;
                }
            }
            if (j - lo < hi - i) { if (lo < j) quicksort(a, b, lo, j); lo = i; }
            else                  { if (i < hi) quicksort(a, b, i, hi); hi = j; }
        }
    }
}
