package com.github.oeuvres.alix.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * A compact automaton over token-id sequences (int[]) for multi-word matching.
 *
 * <p>Build a trie from int[] patterns, optionally minimize to a DAFSA (merge equivalent suffix states),
 * then pack into primitive arrays for fast traversal.</p>
 *
 * <p>Accepting states can still have outgoing arcs (STOP and CONTINUE).</p>
 */
public final class IntAutomaton
{
    /** Packed state -> outgoing arc range. */
    private final int[] offset;
    private final int[] count;

    /** Packed arcs. For each state, arcs are sorted by label. */
    private final int[] label;
    private final int[] target;

    /** Accepting output, or -1 if non-accepting. */
    private final int[] accept;

    /** Maximum pattern length added (useful as lookahead limit). */
    private final int maxLen;

    private IntAutomaton(final int[] offset, final int[] count, final int[] label, final int[] target, final int[] accept, final int maxLen)
    {
        this.offset = offset;
        this.count  = count;
        this.label  = label;
        this.target = target;
        this.accept = accept;
        this.maxLen = maxLen;
    }

    /** Root state is always 0. */
    public int root()
    {
        return 0;
    }

    /** Return accept id for a state, or -1. */
    public int accept(final int state)
    {
        if (state < 0) return -1;
        return accept[state];
    }

    /** Maximum pattern length encountered at build time. */
    public int maxLen()
    {
        return maxLen;
    }

    /**
     * Transition function.
     * @return next state id, or -1 if no transition
     */
    public int step(final int state, final int tokenId)
    {
        if (state < 0) return -1;
        final int n = count[state];
        if (n == 0) return -1;

        final int base = offset[state];

        // For tiny degrees, linear scan usually beats binary search.
        if (n <= 8) {
            for (int i = 0; i < n; i++) {
                if (label[base + i] == tokenId) return target[base + i];
            }
            return -1;
        }

        int lo = base;
        int hi = base + n - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final int v = label[mid];
            if (v < tokenId) lo = mid + 1;
            else if (v > tokenId) hi = mid - 1;
            else return target[mid];
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder
    {
        private static final class Node
        {
            int acceptId = -1;

            int[] labels = new int[4];
            Node[] targets = new Node[4];
            int deg = 0;

            boolean frozen = false;
            int hash = 0;

            Node getOrAdd(final int lab)
            {
                for (int i = 0; i < deg; i++) {
                    if (labels[i] == lab) return targets[i];
                }
                final Node n = new Node();
                addArc(lab, n);
                return n;
            }

            void addArc(final int lab, final Node tgt)
            {
                if (deg == labels.length) {
                    labels  = Arrays.copyOf(labels, deg * 2);
                    targets = Arrays.copyOf(targets, deg * 2);
                }
                labels[deg] = lab;
                targets[deg] = tgt;
                deg++;
            }

            void sortArcsByLabel()
            {
                quicksort(labels, targets, 0, deg - 1);
            }

            void freezeAndHash()
            {
                frozen = true;
                int h = 1_000_003 ^ acceptId;
                for (int i = 0; i < deg; i++) {
                    h = 31 * h + labels[i];
                    h = 31 * h + targets[i].hash;
                }
                hash = h;
            }

            @Override
            public int hashCode()
            {
                return hash;
            }

            @Override
            public boolean equals(final Object o)
            {
                if (this == o) return true;
                if (!(o instanceof Node other)) return false;
                if (acceptId != other.acceptId) return false;
                if (deg != other.deg) return false;

                // arcs must already be sorted and children canonicalized
                for (int i = 0; i < deg; i++) {
                    if (labels[i] != other.labels[i]) return false;
                    if (targets[i] != other.targets[i]) return false; // pointer equality on canonical children
                }
                return true;
            }
        }

        private final Node root = new Node();
        private int maxLen = 0;

        /**
         * Add a pattern (token-id sequence) with its accept/output id.
         * If the same pattern is added twice, the last outputId wins.
         */
        public Builder add(final int[] tokenIds, final int outputId)
        {
            if (tokenIds == null || tokenIds.length == 0) return this;

            Node n = root;
            for (final int id : tokenIds) {
                n = n.getOrAdd(id);
            }
            n.acceptId = outputId;

            if (tokenIds.length > maxLen) maxLen = tokenIds.length;
            return this;
        }

        /** Build a packed automaton. */
        public IntAutomaton build(final boolean minimize)
        {
            final Node canonRoot = minimize ? minimizeToDAFSA(root) : prepareTrie(root);
            return pack(canonRoot, maxLen);
        }

        private static Node prepareTrie(final Node root)
        {
            final List<Node> post = postOrder(root);

            for (final Node n : post) {
                if (n.deg > 1) n.sortArcsByLabel();
            }
            for (final Node n : post) {
                // children already frozen in post-order
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
                // canonicalize children pointers first
                for (int i = 0; i < n.deg; i++) {
                    final Node child = n.targets[i];
                    final Node canon = reg.get(child); // child is already frozen => stable hash/equals
                    if (canon != null) n.targets[i] = canon;
                }

                n.freezeAndHash();

                final Node existing = reg.get(n);
                if (existing == null) {
                    reg.put(n, n);
                }
            }

            final Node canonRoot = reg.get(root);
            return (canonRoot != null) ? canonRoot : root;
        }

        private static IntAutomaton pack(final Node root, final int maxLen)
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
            final int[] offset = new int[nStates];
            final int[] count  = new int[nStates];
            final int[] accept = new int[nStates];
            final int[] label  = new int[arcTotal];
            final int[] target = new int[arcTotal];

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

            return new IntAutomaton(offset, count, label, target, accept, maxLen);
        }

        private static List<Node> postOrder(final Node root)
        {
            final IdentityHashMap<Node, Boolean> seen = new IdentityHashMap<>();
            final ArrayList<Node> out = new ArrayList<>();

            final class Frame {
                Node n; int i;
                Frame(final Node n, final int i) { this.n = n; this.i = i; }
            }

            final ArrayDeque<Frame> st = new ArrayDeque<>();
            st.push(new Frame(root, 0));
            seen.put(root, Boolean.TRUE);

            while (!st.isEmpty()) {
                final Frame f = st.peek();
                if (f.i < f.n.deg) {
                    final Node child = f.n.targets[f.i++];
                    if (seen.put(child, Boolean.TRUE) == null) {
                        st.push(new Frame(child, 0));
                    }
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
                        final int ta = a[i]; a[i] = a[j]; a[j] = ta;
                        final Node tb = b[i]; b[i] = b[j]; b[j] = tb;
                        i++; j--;
                    }
                }

                if (j - lo < hi - i) {
                    if (lo < j) quicksort(a, b, lo, j);
                    lo = i;
                } else {
                    if (i < hi) quicksort(a, b, i, hi);
                    hi = j;
                }
            }
        }
    }
}
