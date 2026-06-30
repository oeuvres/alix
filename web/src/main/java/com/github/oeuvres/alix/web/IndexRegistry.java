package com.github.oeuvres.alix.web;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.oeuvres.alix.lucene.LuceneIndex;

/**
 * Live registry of {@link LuceneIndex} handles backed by a directory of
 * self-describing index directories.
 *
 * <h2>Layout and publication</h2>
 * <p>
 * The registry root holds one subdirectory per corpus. A subdirectory is
 * <em>servable</em> when its name carries no {@linkplain #RESERVED_SUFFIXES
 * reserved suffix}; the subdirectory name is the corpus identifier and the
 * URL segment. A rebuilt corpus is published by swapping directories, for
 * example:
 * </p>
 * <pre>{@code
 * sftp piaget.new            # arriving build; reserved suffix, never served
 * mv piaget piaget.old       # retire the live one for rollback
 * mv piaget.new piaget       # atomic rename publishes the new build
 * }</pre>
 *
 * <h2>Refresh model</h2>
 * <p>
 * A single background thread polls the root every {@code pollMillis}. Each
 * servable directory carries a change token (its filesystem
 * {@code fileKey}, an inode-like identity that the whole-directory rename
 * above necessarily changes). On each poll the registry:
 * </p>
 * <ul>
 *   <li>opens a new directory as a fresh {@link LuceneIndex};</li>
 *   <li>reopens a directory whose token changed, replacing the live handle
 *       and queueing the previous handle for deferred close;</li>
 *   <li>unloads a directory only after it has been missing for at least one
 *       grace window, so the brief gap between the two renames above never
 *       triggers a false unload;</li>
 *   <li>keeps the current handle and remembers the failing token when an
 *       open throws, so a broken build leaves the last good corpus serving
 *       and is not retried until its token changes again.</li>
 * </ul>
 * <p>
 * A superseded or unloaded handle is not closed at once: in-flight queries
 * may still hold its reader. It is queued and closed on a later poll once
 * it has been retired for at least {@code graceMillis}.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * Only the poll thread mutates the internal bookkeeping and reassigns the
 * published map. {@link #get(String)} and {@link #all()} read a
 * {@code volatile} reference and are safe for concurrent use. The initial
 * scan runs synchronously in {@link #start()}, so the first map is ready
 * before any request is served.
 * </p>
 */
public final class IndexRegistry implements Closeable
{
    private static final Logger LOG = Logger.getLogger(IndexRegistry.class.getName());

    /** Directory-name suffixes that mark a non-servable directory. */
    public static final Set<String> RESERVED_SUFFIXES = Set.of(".new", ".tmp", ".old", ".bad");

    private final Path root;
    private final long pollMillis;
    private final long graceMillis;

    /** Published, live handles keyed by corpus name. Swapped atomically. */
    private volatile Map<String, LuceneIndex> live = Map.of();

    /** Poll-thread-only: change token each live handle was opened with. */
    private final Map<String, Object> tokens = new HashMap<>();
    /** Poll-thread-only: last token that failed to open, to avoid retry storms. */
    private final Map<String, Object> failedTokens = new HashMap<>();
    /** Poll-thread-only: first poll at which a once-live name was found missing. */
    private final Map<String, Long> missingSince = new HashMap<>();
    /** Poll-thread-only: superseded handles awaiting deferred close. */
    private final Deque<Retiring> retiring = new ArrayDeque<>();

    private ScheduledExecutorService poller;

    /**
     * Creates a registry over a root directory.
     *
     * @param root directory containing one subdirectory per corpus
     * @param pollMillis interval between filesystem scans, in milliseconds
     * @param graceMillis minimum age before a retired handle is closed and
     *        before a missing directory is unloaded, in milliseconds
     */
    public IndexRegistry(
        final Path root,
        final long pollMillis,
        final long graceMillis
    ) {
        this.root = root.toAbsolutePath().normalize();
        this.pollMillis = pollMillis;
        this.graceMillis = graceMillis;
    }

    /**
     * Returns the live handles, in directory-scan order.
     *
     * @return current corpus handles; the collection is a stable snapshot
     */
    public Collection<LuceneIndex> all()
    {
        return live.values();
    }

    /**
     * Stops polling and closes every handle. Equivalent to {@link #stop()}.
     */
    @Override
    public void close()
    {
        stop();
    }

    /**
     * Returns the live handle for a corpus name, or {@code null} if no such
     * corpus is currently served.
     *
     * @param name corpus identifier (a servable directory name)
     * @return the current handle, or {@code null}
     */
    public LuceneIndex get(
        final String name
    ) {
        if (name == null) {
            return null;
        }
        return live.get(name);
    }

    /**
     * Runs an initial synchronous scan, then schedules periodic scans on a
     * daemon thread. Safe to call once.
     *
     * @throws IllegalStateException if already started
     */
    public synchronized void start()
    {
        if (poller != null) {
            throw new IllegalStateException("IndexRegistry already started");
        }
        scan();
        final ThreadFactory factory = runnable -> {
            final Thread t = new Thread(runnable, "alix-index-scanner");
            t.setDaemon(true);
            return t;
        };
        poller = Executors.newSingleThreadScheduledExecutor(factory);
        poller.scheduleWithFixedDelay(
            this::scanGuarded, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops polling and closes every live and retiring handle. Idempotent.
     */
    public synchronized void stop()
    {
        if (poller != null) {
            poller.shutdownNow();
            try {
                poller.awaitTermination(graceMillis, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            poller = null;
        }
        for (LuceneIndex index : live.values()) {
            closeQuietly(index);
        }
        for (Retiring r : retiring) {
            closeQuietly(r.index);
        }
        live = Map.of();
        tokens.clear();
        failedTokens.clear();
        missingSince.clear();
        retiring.clear();
    }

    private static void closeQuietly(
        final LuceneIndex index
    ) {
        try {
            index.close();
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Error closing index: " + index.name(), e);
        }
    }

    /**
     * Returns whether a directory name is servable (no reserved suffix).
     */
    private static boolean isServable(
        final String name
    ) {
        for (String suffix : RESERVED_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One scan pass. Runs only on the poll thread, so internal bookkeeping
     * needs no locking; only the published {@link #live} map is volatile.
     */
    private void scan()
    {
        final Map<String, Path> servable = listServable();
        final long now = System.currentTimeMillis();
        final Map<String, LuceneIndex> next = new LinkedHashMap<>(live);

        for (Map.Entry<String, Path> e : servable.entrySet()) {
            final String name = e.getKey();
            final Path dir = e.getValue();
            missingSince.remove(name);

            final Object token = tokenOf(dir);
            if (next.containsKey(name) && token.equals(tokens.get(name))) {
                continue;
            }
            if (token.equals(failedTokens.get(name))) {
                continue;
            }

            try {
                final LuceneIndex opened = LuceneIndex.open(dir);
                final LuceneIndex previous = next.put(name, opened);
                tokens.put(name, token);
                failedTokens.remove(name);
                if (previous != null) {
                    retiring.add(new Retiring(previous, now));
                    LOG.info("Reloaded index '" + name + "' from " + dir);
                }
                else {
                    LOG.info("Loaded index '" + name + "' from " + dir);
                }
            }
            catch (Exception ex) {
                failedTokens.put(name, token);
                LOG.log(Level.WARNING,
                    "Keeping previous '" + name + "'; cannot open " + dir + ": " + ex.getMessage(), ex);
            }
        }

        for (String name : new ArrayDeque<>(next.keySet())) {
            if (servable.containsKey(name)) {
                continue;
            }
            final long since = missingSince.computeIfAbsent(name, k -> now);
            if (now - since < graceMillis) {
                continue;
            }
            final LuceneIndex removed = next.remove(name);
            tokens.remove(name);
            failedTokens.remove(name);
            missingSince.remove(name);
            if (removed != null) {
                retiring.add(new Retiring(removed, now));
                LOG.info("Unloaded index '" + name + "' (directory gone)");
            }
        }

        live = next;
        sweepRetiring(now);
    }

    /**
     * Wraps {@link #scan()} so a thrown error cannot silently cancel the
     * scheduled task.
     */
    private void scanGuarded()
    {
        try {
            scan();
        }
        catch (Throwable t) {
            LOG.log(Level.SEVERE, "Index scan failed", t);
        }
    }

    /**
     * Lists servable subdirectories of the root keyed by name. Logs and
     * returns the empty map if the root cannot be read this pass.
     */
    private Map<String, Path> listServable()
    {
        final Map<String, Path> out = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                final String name = dir.getFileName().toString();
                if (isServable(name)) {
                    out.put(name, dir);
                }
            }
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot scan index root: " + root, e);
        }
        return out;
    }

    /**
     * Closes retired handles whose grace window has elapsed.
     */
    private void sweepRetiring(
        final long now
    ) {
        while (!retiring.isEmpty()) {
            final Retiring head = retiring.peekFirst();
            if (now - head.retiredAtMillis < graceMillis) {
                break;
            }
            retiring.pollFirst();
            closeQuietly(head.index);
        }
    }

    /**
     * Computes a change token for an index directory. Two facts are
     * combined:
     * <ul>
     *   <li>the directory's {@code fileKey} (inode-like identity), which a
     *       whole-directory rename necessarily changes;</li>
     *   <li>the signature (name, size, mtime) of the Lucene commit files
     *       ({@code segments*}), which a rebuild necessarily changes.</li>
     * </ul>
     * <p>
     * Either fact alone is changed by a normal publication, so the token is
     * robust to inode reuse on the one hand and to mtime-preserving
     * transfers on the other. Neither fact is touched by the server's own
     * lazy sidecar writes (those carry different file names), so a warmed
     * live index does not appear changed.
     * </p>
     */
    private static Object tokenOf(
        final Path dir
    ) {
        final StringBuilder sb = new StringBuilder();
        try {
            final BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
            final Object key = attrs.fileKey();
            sb.append(key != null ? key.toString() : "nokey");
        }
        catch (IOException e) {
            return "err:" + System.nanoTime();
        }
        sb.append('|');
        final TreeMap<String, String> commit = new TreeMap<>();
        try (DirectoryStream<Path> seg = Files.newDirectoryStream(dir, "segments*")) {
            for (Path p : seg) {
                try {
                    final BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    commit.put(p.getFileName().toString(), a.size() + ":" + a.lastModifiedTime().toMillis());
                }
                catch (IOException ignore) {
                    // a file vanishing mid-scan is itself a change; skip it
                }
            }
        }
        catch (IOException e) {
            sb.append("noseg");
        }
        sb.append(commit);
        return sb.toString();
    }

    /** A handle retired from service, awaiting deferred close. */
    private static final class Retiring
    {
        final LuceneIndex index;
        final long retiredAtMillis;

        Retiring(
            final LuceneIndex index,
            final long retiredAtMillis
        ) {
            this.index = index;
            this.retiredAtMillis = retiredAtMillis;
        }
    }
}
