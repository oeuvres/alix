package alix.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;


/**
 * A sparse matrix to record vectors
 * @author fred
 *
 */
public class SparseMat {
    public HashMap<IntPair, Counter> hash = new HashMap<IntPair, Counter>();
    int length;
    int height;
    int width;
    long sum;
    public int[] rows;
    public int[] rowsIndex;
    public int[] cols;
    public int[] colSizes;
    public double[] counts;
    public double[] rowCounts;
    public double[] colCounts;
    double[] coscounts;
    double[] ppmi;
    double[] deviance;
    double[] devianceMags;
    final static double LOG2 = Math.log(2);
    public static final int COUNT = 0;
    public static final int PPMI = 1;
    public static final int PMI = 2;
    public static final int COSCOUNTS = 3;
    public static final int DEVIANCE = 4;
    
    private static class Entry implements Comparable<Entry>
    {
        final int row;
        final int col;
        final double count;
        Entry(final int row, final int col, final double count)
        {
            this.row = row;
            this.col = col;
            this.count = count;
        }
        @Override
        public int compareTo(Entry o)
        {
            // inspired by Integer.compareTo(x, y)
            if (this.row < o.row) return -1;
            else if (this.row > o.row) return 1;
            else if (this.col < o.col) return -1;
            else if (this.col > o.col) return 1;
            else return 0;
        }
    }
    
    public void compile()
    {
        int length = hash.size();
        Entry[] sorter = new Entry[length];
        int i = 0;
        for (Map.Entry<IntPair, Counter> entry : hash.entrySet()) {
            sorter[i] = new Entry(entry.getKey().x(), entry.getKey().y(), entry.getValue().count);
            i++;
        }
        long start = System.nanoTime();
        /* too slow
        TreeMap<IntPair, Counter> sorted = new TreeMap<>();
        sorted.putAll(hash);
        parallelSort() seems not faster
        */
        Arrays.parallelSort(sorter);
        System.out.println("Sort coocs " + ((System.nanoTime() - start) / 1000000) + " ms");
        i = 0;
        int[] rows = new int[length];
        int[] cols = new int[length];
        double[] counts = new double[length];
        int height = -1;
        int width = -1;
        int row;
        int col;
        for (Entry entry: sorter)
        {
            row = entry.row;
            if (height <= row) height = row + 1;
            rows[i] = row;
            
            col = entry.col;
            if (width <= col) width = col + 1;
            cols[i] = col;
            
            counts[i] = entry.count;
            i++;
        }
        this.rows = rows;
        this.cols = cols;
        this.counts = counts;
        this.width = width;
        this.height = height;
        this.length = length;
        indexRows();
        double count;
        long sum = 0;
        double[] rowCounts = new double[height];
        double[] colCounts = new double[width];
        int[] colSizes = new int[width];
        for(i=0; i < length; i++) {
            count = counts[i];
            rowCounts[rows[i]] += count;
            colCounts[cols[i]] += count;
            colSizes[cols[i]]++;
            sum += count;
        }
        this.sum = sum;
        this.rowCounts = rowCounts;
        this.colCounts = colCounts;
        this.colSizes = colSizes;
    }
    
    
    public void indexRows()
    {
        int[] rowsIndex = new int[height+1];
        Arrays.fill(rowsIndex, -1);
        int row;
        int rowLast = -1;
        int length = rows.length;
        int[] rows = this.rows;
        for (int i = 0; i < length; i++)
        {
            row = rows[i];
            if (row != rowLast) rowsIndex[row] = i;
            rowLast = row;
        }
        rowsIndex[height] = length;
        this.rowsIndex = rowsIndex;
    }

    /**
     * Comput ppmi values
     */
    public void deviance()
    {
        double[] deviance = new double[length];
        double[] devianceMags = new double[height];
        double cell;
        for(int i=0; i < length; i++) {
            cell = (double)counts[i] - ((double)colCounts[cols[i]] / (double)colSizes[cols[i]]);
            deviance[i] = cell;
            devianceMags[rows[i]] += cell * cell;
        }
        for (int i=0; i < height; i++) {
            devianceMags[i] = Math.sqrt(devianceMags[i]);
        }
        this.deviance = deviance;
        this.devianceMags = devianceMags;
    }

    /**
     * Comput ppmi values
     */
    public void ppmi(final float laplace)
    {
        int[] rows = this.rows;
        int[] cols = this.cols;
        int length = counts.length;
        double[] ppmi = new double[length];
        double sum = (double)width * (double)height * (double)laplace; // cast is needed, or will be negative long
        double[] rowCount = new double[height];
        for(int i=0; i < height; i++) rowCount[i] = laplace * width;
        double[] colCount = new double[width];
        for(int i=0; i < width; i++) colCount[i] = laplace * height;
        double count;
        for(int i=0; i < length; i++) {
            count = counts[i];
            colCount[cols[i]] += count;
            rowCount[rows[i]] += count;
            sum += count;
        }
        double[] rowMag = new double[height];
        for(int i=0; i < length; i++) {
            double cell = Math.log( (double)((counts[i]+laplace)*sum) / (double)(rowCount[rows[i]] * colCount[cols[i]]) ) / LOG2;
            if (cell < 0) cell = 0;
            else rowMag[rows[i]] += cell * cell;
            ppmi[i] = cell;
        }
        // prepare data for cosine distance
        for (int i=0; i < height; i++) {
            rowMag[i] = Math.sqrt(rowMag[i]);
        }
        for (int i=0; i < length; i++) {
            double mag = rowMag[rows[i]];
            if (mag == 0) ppmi[i] = 0;
            else ppmi[i] /= rowMag[rows[i]];
        }
        this.ppmi = ppmi;
    }


    
    /*
    public Top<Integer> topLabbe(int row, int topSize) 
    {
        Top<Integer> top = new Top<Integer>(topSize);
        int height = this.height;
        for(int i = 0; i < height; i++) {
            double dist = labbe(row, i);
            top.push(1 - dist, i);
        }
        return top;
    }
    */
    public double cosine(final int row1, final int row2, final double[] values)
    {
        int i1 = rowsIndex[row1];
        int i1End = rowsIndex[row1+1] - 1;
        int i2 = rowsIndex[row2];
        int i2End = rowsIndex[row2+1] - 1;
        double dist = 0;
        double mag1 = 0;
        double mag2 = 0;
        double value1;
        double value2;
        while (true) {
            int col1 = cols[i1];
            int col2 = cols[i2];
            if(col1 < col2) {
                value1 = values[i1];
                mag1 += value1 * value1;
                if (i1 == i1End && i2 == i2End) break;
                i1++;
            }
            else if(col1 > col2) {
                value2 = values[i2];
                mag2 += value2 * value2;
                if (i1 == i1End && i2 == i2End) break;
                i2++;
            }
            else if (col1 == col2) {
                value1 = values[i1];
                value2 = values[i2];
                dist += value1 * value2;
                mag1 += value1 * value1;
                mag2 += value2 * value2;
                if (i1 == i1End && i2 == i2End) break;
                if (i1 != i1End) i1++;
                if (i2 != i2End) i2++;
            }
        }
        return dist / (Math.sqrt(mag1) * Math.sqrt(mag2));
    }

    /* Test of a Labbé distance, very bad.
    public double labbe(int row1, int row2)
    {
        int aI, aIstop, bI, bIstop;
        double aN, bN;
        if (rowCounts[row1] < rowCounts[row2]) {
            aN = rowCounts[row1];
            aI = rowsIndex[row1];
            aIstop = rowsIndex[row1+1];
            bN = rowCounts[row2];
            bI = rowsIndex[row2];
            bIstop = rowsIndex[row2+1];
        }
        else {
            aN = rowCounts[row2];
            aI = rowsIndex[row2];
            aIstop = rowsIndex[row2+1];
            bN = rowCounts[row1];
            bI = rowsIndex[row1];
            bIstop = rowsIndex[row1+1];
        }
        double bE;
        double dist = 0;
        double u = (double)aN / (double)bN;
        while (true) {
            int aCol = cols[aI];
            int bCol = cols[bI];
            if (aCol == bCol) {
                bE = (double)counts[bI] * u;
                double dif = Math.abs(bE - counts[aI]);
                if (dif >= 0.5) dist += dif;
                aI++;
                bI++;
            }
            else if(aCol < bCol) { // bE = 0
                dist += counts[aI];
                aI++;
            }
            else if(bCol < aCol) { // aE = 0
                bE = (double)counts[bI] * u;
                if (bE >= 1) dist += bE; // do not add B counts when less than 1
                bI++;
            }
            if (aI >= aIstop || bI >= bIstop) break;
        }
        dist /= (2*aN);
        return dist;
    }
    */
    
    public Top<Integer> cosine(int row, int mode, int topSize) 
    {
        Top<Integer> top = new Top<Integer>(topSize);
        int length = rows.length;
        int iStart = rowsIndex[row];
        int iEnd = rowsIndex[row + 1];
        int rowLast = rows[0];
        int i = iStart;
        int j = 0;
        double dist = 0;
        int col1;
        int col2;
        int row2;
        while (true) {
            // end of row ?
            if (i == iEnd || j == length || rows[j] != rowLast) {
                if (mode == DEVIANCE) dist /= (devianceMags[row] * devianceMags[rowLast]);
                top.push(dist, rowLast);
                // jump to next working index
                int a = rowLast+1;
                do {
                    if(a >= height) return top; // end of list
                    j = rowsIndex[a];
                    a++;
                }
                while(j < 0);
                dist = 0;
                i = iStart;
            }
            col1 = cols[i];
            row2 = rows[j];
            col2 = cols[j];
            if (col1 == col2) {
                if (mode == PPMI) dist += ppmi[i] * ppmi[j];
                else if (mode == DEVIANCE) dist += deviance[i] * deviance[j];
                else dist += coscounts[i] * coscounts[j];
                i++;
                j++;
            }
            else if(col1 < col2) {
                i++;
            }
            else if(col2 < col1) {
                j++;
            }
            rowLast = row2;
        }
    }

    public void write(String dstFile) throws IOException 
    {
        int length = counts.length;
        try(FileOutputStream out = new FileOutputStream(dstFile)) {
            FileChannel file = out.getChannel();
            ByteBuffer buf = file.map(FileChannel.MapMode.READ_WRITE, 0, 4 * 3 * length);
            for(int i = 0; i < length; i++) {
                buf.putInt(rows[i]).putInt(cols[i]).putDouble(counts[i]);
            }
            file.close();
        }
    }
    public void read(String srcFile) throws IOException 
    {
        /* TODO
        try(FileOutputStream in = new FileOutputStream(srcFile)) {
            FileChannel file = in.getChannel();
            ByteBuffer buf = file.map(FileChannel.MapMode.READ_WRITE, 0, 4 * 3 * length);
            for(int i = 0; i < length; i++) {
                buf.putInt(rows[i]).putInt(cols[i]).putInt(counts[i]);
            }
            file.close();
        }
        */
    }
    public static class Counter 
    {
        private double count = 0;
        public Counter(double count) {
            this.count = count;
        }
        public void inc() {
            count++;
        }
        public void add(double add) {
            count += add;
        }
        @Override
        public String toString()
        {
            return Double.toString(count);
        }        
    }
    @Override
    public String toString()
    {
        return toString(COUNT);
    }
    public String toString(final int mode)
    {
        DecimalFormat df = new DecimalFormat("0.####");
        StringBuffer sb = new StringBuffer();
        int length = counts.length;
        int rowLast = 0;
        int colLast = 0;
        for(int i = 0; i < length; i++) {
            int row = rows[i];
            int col = cols[i];
            if (rowLast != row) {
                sb.append('\n');
                colLast= 0;
                rowLast = row;
            }
            
            for(int j = (col - colLast -1); j > 0; j--) {
                sb.append("\t");
            }
            if (mode == PPMI) sb.append(df.format(ppmi[i])+'\t');
            // else if (mode == PMI) sb.append(df.format(pmi[i])+'\t');
            else if (mode == COSCOUNTS) sb.append(df.format(coscounts[i])+'\t');
            else sb.append(""+counts[i]+'\t');
            colLast = col;
        }
        return sb.toString();
    }
    
    public static void main(String[] args) throws Exception
    {
        SparseMat mat = new SparseMat();
        mat.hash.put(new IntPair(2, 0), new Counter(5));
        mat.hash.put(new IntPair(2, 2), new Counter(5));
        mat.hash.put(new IntPair(2, 4), new Counter(5));
        mat.hash.put(new IntPair(0, 0), new Counter(10));
        mat.hash.put(new IntPair(0, 2), new Counter(10));
        mat.hash.put(new IntPair(0, 4), new Counter(10));
        mat.hash.put(new IntPair(0, 10), new Counter(10));
        mat.hash.put(new IntPair(1, 0), new Counter(10));
        mat.hash.put(new IntPair(1, 1), new Counter(3));
        mat.hash.put(new IntPair(1, 3), new Counter(10));
        mat.hash.put(new IntPair(1, 5), new Counter(10));
        mat.compile();
        System.out.println("height="+mat.height+" width="+mat.width);
        System.out.println(mat);
        int rowLength = 3;
        for(int i=0; i < rowLength; i++) {
            for(int j=0; j < rowLength; j++) {
                System.out.println("cosine("+i+", "+j+") = "+mat.cosine(i, j, mat.counts));
            }
        }
    }
}
