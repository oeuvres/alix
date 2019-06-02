package alix.grep.query;

import alix.fr.dic.Tag;
import alix.util.Occ;

public class TestTag extends Test
{
  int tag;

  public TestTag(int tag) {
    this.tag = tag;
  }

  @Override
  public boolean test(Occ occ)
  {
    return (occ.tag().code() == tag);
  }

  @Override
  public String label()
  {
    if (Tag.group(tag) == tag)
      return '"' + Tag.label(tag) + '"';
    return Tag.label(tag);
  }

}
