package alix.util;


public class Edge implements Comparable<Edge>
{
    public final int source;
    public final int target;
    public final double score;
    private int hash;
    
    public Edge(final int source, final int target, final double score)
    {
        this.source = source;
        this.target = target;
        this.score = score;
    }
    
    public Edge(final int source, final int target, final double score, boolean directed)
    {
        this.score = score;
        if (directed) {
            this.source = source;
            this.target = target;
        }
        else if (source < target) {
            this.source = source;
            this.target = target;
        } 
        else {
            this.source = target;
            this.target = source;
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (o == null)
            return false;
        if (o == this)
            return true;
        if (o instanceof Edge) {
            Edge edge = (Edge) o;
            return (this.source == edge.source && this.target == edge.target);
        }
        if (o instanceof IntPair) {
            IntPair pair = (IntPair) o;
            return (this.source == pair.x && this.target == pair.y);
        }
        if (o instanceof IntSeries) {
            IntSeries series = (IntSeries) o;
            if (series.length() != 2)
                return false;
            if (this.source != series.data[0])
                return false;
            if (this.target != series.data[1])
                return false;
            return true;
        }
        return false;
    }
    
    @Override
    public int hashCode() {
        if (hash != 0) return hash;
        // hash = ( y << 16 ) ^ x; // 0% collision, but less dispersion
        hash = ((this.source + this.target) * (this.source + this.target + 1) / 2) + this.target;
        // hash = (31 * 17 + x) * 31 + y; // 97% collision
        return hash;
    }
    
    @Override
    public int compareTo(Edge o)
    {
        int cp = Double.compare(o.score, score);
        if (cp != 0) {
            return cp;
        }
        if (this.source > o.source) return 1;
        if (this.source < o.source) return -1;
        if (this.target > o.target) return 1;
        if (this.target < o.target) return -1;
        return 0;
    }
    @Override
    public String toString()
    {
        return "" + source + "->" + target + " (" + score + ")";
    }
}
