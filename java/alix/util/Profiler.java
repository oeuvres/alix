package alix.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.instrument.Instrumentation;

public class Profiler
{
  private static Instrumentation instrumentation;

  public static void premain(String args, Instrumentation inst)
  {
    instrumentation = inst;
  }

  public static long getObjectSize(Object o)
  {
    return instrumentation.getObjectSize(o);
  }

  /**
   * Works only with Serializable object
   * 
   * @param o
   * @return
   * @throws IOException
   */
  public static long serSize(Object o) throws IOException
  {
    Serializable ser = null;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream oos = new ObjectOutputStream(baos);
    oos.writeObject(ser);
    oos.close();
    return baos.size();
  }
}
