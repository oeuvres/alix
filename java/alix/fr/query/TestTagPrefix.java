package alix.fr.query;

import alix.fr.Tag;
import alix.util.Occ;

public class TestTagPrefix extends Test
{
  int prefix;

  public TestTagPrefix(int tag) {
    this.prefix = tag;
  }

  @Override
  public boolean test(Occ occ)
  {
    return (occ.tag().prefix() == prefix);
  }

  @Override
  public String label()
  {
    return Tag.label(prefix);
  }

}
