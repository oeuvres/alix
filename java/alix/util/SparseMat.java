/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache licence.
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
package alix.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A sparse matrix to record vectors
 * 
 * @author fred
 *
 */
public class SparseMat
{
  public HashMap<IntPair, Counter> hash = new HashMap<IntPair, Counter>();
  int length;
  int height;
  int width;
  double sum;
  int[] rows;
  int[] rowIndex;
  int[] rowSize;
  int[] colSize;
  int[] cols;
  double[] counts;
  double[] countsRowSum;
  double[] countsColSum;
  double[] countsRowMag;
  double[] ppmi;
  double[] ppmiRowMag;
  final static double LOG2 = Math.log(2);
  public static final int COUNTS = 0;
  public static final int PPMI = 1;
  public static final int CELL_BYTE_LEN = 4 + 4 + 8;

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
    sortData();
    indexRows();
    sumLines();
    countsRowMags();
  }

  public void sortData()
  {
    int length = hash.size();
    Entry[] sorter = new Entry[length];
    int i = 0;
    for (Map.Entry<IntPair, Counter> entry : hash.entrySet()) {
      sorter[i] = new Entry(entry.getKey().x(), entry.getKey().y(), entry.getValue().count);
      i++;
    }
    long start = System.nanoTime();
    /*
     * too slow TreeMap<IntPair, Counter> sorted = new TreeMap<>();
     * sorted.putAll(hash); parallelSort() seems not faster
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
    for (Entry entry : sorter) {
      row = entry.row;
      if (height <= row) height = row + 1;
      rows[i] = row;

      col = entry.col;
      if (width <= col) width = col + 1;
      cols[i] = col;

      counts[i] = entry.count;
      i++;
    }
    hash.clear();
    this.rows = rows;
    this.cols = cols;
    this.counts = counts;
    this.width = width;
    this.height = height;
    this.length = length;
  }

  public void indexRows()
  {
    int[] rowsIndex = new int[height + 1];
    Arrays.fill(rowsIndex, -1);
    int row;
    int rowLast = -1;
    int length = rows.length;
    int[] rows = this.rows;
    for (int i = 0; i < length; i++) {
      row = rows[i];
      if (row != rowLast) rowsIndex[row] = i;
      rowLast = row;
    }
    rowsIndex[height] = length;
    this.rowIndex = rowsIndex;
  }

  public void sumLines()
  {
    double count;
    double sum = 0;
    double[] rowSum = new double[height];
    double[] colSum = new double[width];
    int[] rowSize = new int[height];
    int[] colSize = new int[width];
    for (int i = 0; i < length; i++) {
      count = counts[i];
      rowSum[rows[i]] += count;
      colSum[cols[i]] += count;
      rowSize[rows[i]]++;
      colSize[cols[i]]++;
      sum += count;
    }
    this.sum = sum;
    this.countsRowSum = rowSum;
    this.countsColSum = colSum;
    this.rowSize = rowSize;
    this.colSize = colSize;
  }

  public void countsRowMags()
  {
    double[] values = this.counts;
    countsRowMag = new double[height];
    for (int row = 0; row < height; row++) {
      int start = rowIndex[row];
      int end = rowIndex[row + 1];
      float mag = 0;
      for (int i = start; i < end; i++) {
        mag += values[i] * values[i];
      }
      countsRowMag[row] = Math.sqrt(mag);
    }
  }

  /**
   * Comput ppmi values
   */
  public void ppmi(double laplace)
  {
    double cds = 1;
    int[] rows = this.rows;
    int[] cols = this.cols;
    int length = counts.length;
    double[] rowSum = new double[height];
    for (int i = 0; i < height; i++)
      rowSum[i] = laplace * (width - rowSize[i]);
    double[] colSum = new double[width];
    double cdsLaplace = Math.pow(laplace, cds);
    for (int i = 0; i < width; i++)
      colSum[i] = cdsLaplace * (height - colSize[i]);

    double count;
    // double ppmiSum = 0;
    // pop sums
    for (int i = 0; i < length; i++) {
      count = counts[i];
      rowSum[rows[i]] += (count + laplace);
      colSum[cols[i]] += (count + laplace);
    }
    // for (int i=0; i < height; i++) rowSum[i] /= ppmiSum;
    // for (int i=0; i < width; i++) colSum[i] /= ppmiSum;

    double[] ppmi = new double[length];
    double[] rowMag = new double[height];
    for (int i = 0; i < length; i++) {
      double cell = Math.log((double) ((counts[i] + laplace) * sum) / (double) (rowSum[rows[i]] * colSum[cols[i]]))
          / LOG2;
      if (cell < 0) cell = 0;
      else rowMag[rows[i]] += cell * cell;
      ppmi[i] = cell;
    }
    for (int i = 0; i < height; i++)
      rowMag[i] = Math.sqrt(rowMag[i]);
    this.ppmiRowMag = rowMag;
    this.ppmi = ppmi;
  }

  public Top<Integer> sims(int row, final int mode, int topSize)
  {
    Top<Integer> top = new Top<Integer>(topSize);
    int height = this.height;
    double dist;
    for (int i = 0; i < height; i++) {
      if (mode == SparseMat.PPMI) dist = cosine(row, i, ppmi, ppmiRowMag);
      else dist = cosine(row, i, counts, countsRowMag);
      top.push(dist, i);
    }
    return top;
  }

  public double cosine(final int row1, final int row2, final double[] values, final double[] mags)
  {
    int i1 = rowIndex[row1];
    int i1End = rowIndex[row1 + 1];
    int i2 = rowIndex[row2];
    int i2End = rowIndex[row2 + 1];
    double dist = 0;
    while (true) {
      if (i1 == i1End || i2 == i2End) {
        break;
      }
      /*
       * if(i1 == i1End) { // finish row2 i2++; continue; } if(i2 == i2End) { //
       * finish row1 i1++; continue; }
       */
      int col1 = cols[i1];
      int col2 = cols[i2];
      if (col1 < col2) { // forward row1
        i1++;
      }
      else if (col1 > col2) { // forward row2
        i2++;
      }
      else if (col1 == col2) { // row1 and row2 intersection
        dist += values[i1] * values[i2];
        i1++;
        i2++;
      }
    }
    if (mags[row1] == 0 || mags[row2] == 0) return 0;
    return dist / (mags[row1] * mags[row2]);
  }

  /*
   * Test of a Labbé distance, very bad. public double labbe(int row1, int row2) {
   * int aI, aIstop, bI, bIstop; double aN, bN; if (rowCounts[row1] <
   * rowCounts[row2]) { aN = rowCounts[row1]; aI = rowsIndex[row1]; aIstop =
   * rowsIndex[row1+1]; bN = rowCounts[row2]; bI = rowsIndex[row2]; bIstop =
   * rowsIndex[row2+1]; } else { aN = rowCounts[row2]; aI = rowsIndex[row2];
   * aIstop = rowsIndex[row2+1]; bN = rowCounts[row1]; bI = rowsIndex[row1];
   * bIstop = rowsIndex[row1+1]; } double bE; double dist = 0; double u =
   * (double)aN / (double)bN; while (true) { int aCol = cols[aI]; int bCol =
   * cols[bI]; if (aCol == bCol) { bE = (double)counts[bI] * u; double dif =
   * Math.abs(bE - counts[aI]); if (dif >= 0.5) dist += dif; aI++; bI++; } else
   * if(aCol < bCol) { // bE = 0 dist += counts[aI]; aI++; } else if(bCol < aCol)
   * { // aE = 0 bE = (double)counts[bI] * u; if (bE >= 1) dist += bE; // do not
   * add B counts when less than 1 bI++; } if (aI >= aIstop || bI >= bIstop)
   * break; } dist /= (2*aN); return dist; }
   */

  public void save(String dstFile) throws IOException
  {
    int length = counts.length;
    try (FileChannel channel = new RandomAccessFile(dstFile, "rw").getChannel();) {
      MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, (CELL_BYTE_LEN) * length);
      for (int i = 0; i < length; i++) {
        buf.putInt(rows[i]).putInt(cols[i]).putDouble(counts[i]);
      }
    }
  }

  public void load(String srcFile) throws IOException
  {
    int length;
    int[] rows;
    int[] cols;
    double[] counts;
    int height = 0;
    int width = 0;
    try (FileChannel channel = new FileInputStream(srcFile).getChannel();) {
      long size = channel.size();
      length = (int) (size / CELL_BYTE_LEN);
      MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
      rows = new int[length];
      cols = new int[length];
      counts = new double[length];
      for (int i = 0; i < length; i++) {
        int row = buf.getInt();
        if (height <= row) height = row + 1;
        rows[i] = row;

        int col = buf.getInt();
        if (width <= col) width = col + 1;
        cols[i] = col;

        counts[i] = buf.getDouble();
      }
      channel.close();
    }
    this.length = length;
    this.rows = rows;
    this.cols = cols;
    this.counts = counts;
    this.width = width;
    this.height = height;
    indexRows();
    sumLines();
    countsRowMags();
  }

  public static class Counter
  {
    private double count = 0;

    public Counter(double count)
    {
      this.count = count;
    }

    public void inc()
    {
      count++;
    }

    public void add(double add)
    {
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
    return toString(COUNTS);
  }

  public String toString(final int mode)
  {
    DecimalFormat df = new DecimalFormat("0.####");
    StringBuffer sb = new StringBuffer();
    int length = counts.length;
    int rowLast = 0;
    int colLast = 0;
    for (int i = 0; i < length; i++) {
      int row = rows[i];
      int col = cols[i];
      if (rowLast != row) {
        sb.append('\n');
        colLast = 0;
        rowLast = row;
      }

      for (int j = (col - colLast - 1); j > 0; j--) {
        sb.append("\t");
      }
      if (mode == PPMI) sb.append(df.format(ppmi[i]) + '\t');
      // else if (mode == PMI) sb.append(df.format(pmi[i])+'\t');
      else sb.append("" + counts[i] + '\t');
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
    // mat.hash.put(new IntPair(0, 10), new Counter(10));
    mat.hash.put(new IntPair(1, 0), new Counter(10));
    mat.hash.put(new IntPair(1, 1), new Counter(3));
    mat.hash.put(new IntPair(1, 3), new Counter(10));
    mat.hash.put(new IntPair(1, 5), new Counter(10));
    mat.compile();
    System.out.println(mat);
    System.out.print("rowSize");
    System.out.println(Arrays.toString(mat.rowSize));
    System.out.print("colSize");
    System.out.println(Arrays.toString(mat.colSize));
    System.out.println("sum = " + mat.sum);
    mat.save("test.dat");
    System.out.println("     --- save, load");
    mat.load("test.dat");
    System.out.println(mat);
    mat.ppmi(0);
    System.out.println("height=" + mat.height + " width=" + mat.width);
    System.out.println(mat.toString(SparseMat.PPMI));
    System.out.println(Arrays.toString(mat.ppmiRowMag));
    int rowLength = 3;
    for (int i = 0; i < rowLength; i++) {
      for (int j = 0; j < rowLength; j++) {
        System.out.println("\ncosine(" + i + ", " + j + ")");
        System.out.println(mat.cosine(i, j, mat.ppmi, mat.ppmiRowMag));
      }
    }
  }
}
