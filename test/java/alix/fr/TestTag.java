package alix.fr;

import java.io.IOException;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;

public class TestTag
{
  /**
   * For testing
   * 
   * @throws IOException
   */
  public static void main(String[] args)
  {
    System.out.println(Tag.CODE);
    System.out.println("test equals " + new Tag(Tag.SUB).equals(Tag.SUB));
    System.out.println(Tag.isName(Tag.NAMEplace));
    System.out.println("is NAMEplace a prefix ? " + Tag.isGroup(Tag.NAMEplace));
    System.out.println("is NAME a prefix ? " + Tag.isGroup(Tag.NAME));
    System.out.println("UNKNOW tag " + Tag.code("TEST"));
    System.out.println("prefix label by number category ADVint : " + Tag.group(Tag.ADVinter));
    TagFilter filter = new TagFilter();
    filter.setGroup(Tag.VERBppass);
    filter.setGroup(Tag.NAMEauthor);
    System.out.println(filter);
  }

}
