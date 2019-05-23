package alix.fail;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

import alix.fr.dic.Tag;
import alix.lucene.CharsAtt;
import alix.lucene.CharsAttMaps.LexEntry;
import alix.lucene.CharsAttMaps.NameEntry;
import alix.util.Chain;
import alix.util.CsvReader;
import sun.misc.Unsafe;

/**
 * https://github.com/jkubrynski/serialization-tests/blob/master/src/main/java/com/kubrynski/poc/serial/ObjectWriteTest.java
 * @author Jakub Kubrynski <jkubrynski@gmail.com>
 * @since 29.07.12
 */
public class MemBuf {
  public static final int MAX_BUFFER_SIZE = 1024;
  public static final Unsafe UNSAFE;
  static {
    try {
      Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      UNSAFE = (Unsafe) theUnsafe.get(null);
    } catch (Exception e) {
      throw new RuntimeException("Unable to load unsafe", e);
    }
  }
  private final byte[] buffer;

  private static final long byteArrayOffset = UNSAFE.arrayBaseOffset(byte[].class);
  private static final long charArrayOffset = UNSAFE.arrayBaseOffset(char[].class);
  private static final long longArrayOffset = UNSAFE.arrayBaseOffset(long[].class);

  private static final int SIZE_OF_BOOLEAN = 1;
  private static final int SIZE_OF_INT = 4;
  private static final int SIZE_OF_LONG = 8;

  private long pos = 0;

  public MemBuf(int bufferSize) {
    this.buffer = new byte[bufferSize];
  }

  public final void putLong(long value) {
    UNSAFE.putLong(buffer, byteArrayOffset + pos, value);
    pos += SIZE_OF_LONG;
  }

  public final long getLong() {
    long result = UNSAFE.getLong(buffer, byteArrayOffset + pos);
    pos += SIZE_OF_LONG;
    return result;
  }

  public final void putInt(int value) {
    UNSAFE.putInt(buffer, byteArrayOffset + pos, value);
    pos += SIZE_OF_INT;
  }

  public final int getInt() {
    int result = UNSAFE.getInt(buffer, byteArrayOffset + pos);
    pos += SIZE_OF_INT;
    return result;
  }

  public final void putLongArray(final long[] values) {
    putInt(values.length);
    long bytesToCopy = values.length << 3;
    UNSAFE.copyMemory(values, longArrayOffset, buffer, byteArrayOffset + pos, bytesToCopy);
    pos += bytesToCopy;
  }


  public final long[] getLongArray() {
    int arraySize = getInt();
    long[] values = new long[arraySize];
    long bytesToCopy = values.length << 3;
    UNSAFE.copyMemory(buffer, byteArrayOffset + pos, values, longArrayOffset, bytesToCopy);
    pos += bytesToCopy;
    return values;
  }

  public final void putCharArray(final char[] values) {
    putInt(values.length);
    long bytesToCopy = values.length << 1;
    UNSAFE.copyMemory(values, charArrayOffset, buffer, byteArrayOffset + pos, bytesToCopy);
    pos += bytesToCopy;
  }


  public final char[] getCharArray() {
    int arraySize = getInt();
    char[] values = new char[arraySize];
    long bytesToCopy = values.length << 1;
    UNSAFE.copyMemory(buffer, byteArrayOffset + pos, values, charArrayOffset, bytesToCopy);
    pos += bytesToCopy;
    return values;
  }

  public final void putBoolean(boolean value) {
    UNSAFE.putBoolean(buffer, byteArrayOffset + pos, value);
    pos += SIZE_OF_BOOLEAN;
  }

  public final boolean getBoolean() {
    boolean result = UNSAFE.getBoolean(buffer, byteArrayOffset + pos);
    pos += SIZE_OF_BOOLEAN;
    return result;
  }

  public final byte[] getBuffer() {
    return buffer;
  }
  public static void main(String[] args) throws ParseException, IOException
  {
    Reader reader;
    CsvReader csv;
    HashMap<CharsAtt, LexEntry> WORD = new HashMap<CharsAtt, LexEntry>((int) (150000 * 0.75));
    long time;
    int count;
    
    time = System.nanoTime();
    reader = new InputStreamReader(Tag.class.getResourceAsStream("word.csv"), StandardCharsets.UTF_8);
    csv = new CsvReader(reader, 3);
    csv.readRow();
    count = 0;
    int no = 0;
    while (csv.readRow()) {
      Chain orth = csv.row().get(0);
      if (orth.isEmpty() || orth.charAt(0) == '#') continue;
      if (WORD.containsKey(orth)) continue;
      CharsAtt key = new CharsAtt(orth);
      WORD.put(key, new LexEntry(csv.row().get(1), csv.row().get(2)));
      count++;
    }
    System.out.println("Hum CSV: " + ((System.nanoTime() - time) / 1000000) + " ms count="+count);
    System.out.println(WORD.size());
    
    // write buffer
    RandomAccessFile raf = null;
    /*
    try {
      MemBuf memBuf = new MemBuf(MAX_BUFFER_SIZE);
      raf = new RandomAccessFile(fileName, "rw");
      test.write(memoryBuffer);
      raf.write(memoryBuffer.getBuffer());
    } finally {
      if (raf != null) {
        raf.close();
      }
    }
    */
    /*
    LexEntry entry;
    for (int loop = 0; loop < 10; loop++) {
      time = System.nanoTime();
      count = no = 0;
      for (int i = 0; i < size; i++) {
        entry = IMWORD.get(keys[i]);
        if (entry == null) count++;
        else no++;
      }
      System.out.println("Immutable map: " + ((System.nanoTime() - time) / 1000000) + " ms count="+count+" no="+no);
      time = System.nanoTime();
      count = no = 0;
      for (int i = 0; i < size; i++) {
        entry = WORD.get(keys[i]);
        if (entry == null) count++;
        else no++;
      }
      System.out.println("normal map: " + ((System.nanoTime() - time) / 1000000) + " ms count="+count+" no="+no);
    }
    */
    /*
    String filename = "test.ser";
    // write simple dic
    RandomAccessFile raf = null;
    try {
      MemBuf memBuf = new MemBuf(MAX_BUFFER_SIZE);
      raf = new RandomAccessFile(filename, "rw");
      int size = WORD.size();
      memBuf.putInt(value);
      test.write(memoryBuffer);
      raf.write(memoryBuffer.getBuffer());
    } finally {
      if (raf != null) {
        raf.close();
      }
    }
    */

  }

}