package alix.deprecated;

import java.lang.reflect.Field;

import sun.misc.Unsafe;

/**
 * https://github.com/jkubrynski/serialization-tests/blob/master/src/main/java/com/kubrynski/poc/serial/ObjectWriteTest.java
 * 
 * @author Jakub Kubrynski <jkubrynski@gmail.com>
 * @since 29.07.12
 */
public class MemBuf
{
  public static final int MAX_BUFFER_SIZE = 1024;
  public static final Unsafe UNSAFE;
  static {
    try {
      Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      UNSAFE = (Unsafe) theUnsafe.get(null);
    }
    catch (Exception e) {
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

  public MemBuf(int bufferSize)
  {
    this.buffer = new byte[bufferSize];
  }

  public final void putLong(long value)
  {
    UNSAFE.putLong(buffer, byteArrayOffset + pos, value);
    pos += SIZE_OF_LONG;
  }

  public final long getLong()
  {
    long result = UNSAFE.getLong(buffer, byteArrayOffset + pos);
    pos += SIZE_OF_LONG;
    return result;
  }

  public final void putInt(int value)
  {
    UNSAFE.putInt(buffer, byteArrayOffset + pos, value);
    pos += SIZE_OF_INT;
  }

  public final int getInt()
  {
    int result = UNSAFE.getInt(buffer, byteArrayOffset + pos);
    pos += SIZE_OF_INT;
    return result;
  }

  public final void putLongArray(final long[] values)
  {
    putInt(values.length);
    long bytesToCopy = values.length << 3;
    UNSAFE.copyMemory(values, longArrayOffset, buffer, byteArrayOffset + pos, bytesToCopy);
    pos += bytesToCopy;
  }

  public final long[] getLongArray()
  {
    int arraySize = getInt();
    long[] values = new long[arraySize];
    long bytesToCopy = values.length << 3;
    UNSAFE.copyMemory(buffer, byteArrayOffset + pos, values, longArrayOffset, bytesToCopy);
    pos += bytesToCopy;
    return values;
  }

  public final void putCharArray(final char[] values)
  {
    putInt(values.length);
    long bytesToCopy = values.length << 1;
    UNSAFE.copyMemory(values, charArrayOffset, buffer, byteArrayOffset + pos, bytesToCopy);
    pos += bytesToCopy;
  }

  public final char[] getCharArray()
  {
    int arraySize = getInt();
    char[] values = new char[arraySize];
    long bytesToCopy = values.length << 1;
    UNSAFE.copyMemory(buffer, byteArrayOffset + pos, values, charArrayOffset, bytesToCopy);
    pos += bytesToCopy;
    return values;
  }

  public final void putBoolean(boolean value)
  {
    UNSAFE.putBoolean(buffer, byteArrayOffset + pos, value);
    pos += SIZE_OF_BOOLEAN;
  }

  public final boolean getBoolean()
  {
    boolean result = UNSAFE.getBoolean(buffer, byteArrayOffset + pos);
    pos += SIZE_OF_BOOLEAN;
    return result;
  }

  public final byte[] getBuffer()
  {
    return buffer;
  }

}