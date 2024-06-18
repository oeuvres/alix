package com.github.oeuvres.alix.util;

import java.util.Iterator;

/**
 * Iterator of edges backed on a sorted array.
 */
public class EdgeTop implements Iterator<Edge>
{
    private final Edge[] edges;
    private int cursor = 0;

    EdgeTop(final Edge[] edges) {
        this.edges = edges;
    }

    public int length()
    {
        return edges.length;
    }

    @Override
    public boolean hasNext()
    {
        if (cursor < edges.length) {
            return true;
        }
        return false;
    }

    @Override
    public Edge next()
    {
        Edge edge = edges[cursor];
        cursor++;
        return edge;
    }

}