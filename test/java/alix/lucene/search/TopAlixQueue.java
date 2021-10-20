package alix.lucene.search;

import org.apache.lucene.util.PriorityQueue;

public class TopAlixQueue extends PriorityQueue<TopAlixScore> {

  public TopAlixQueue(int maxSize) {
    super(maxSize);
    // TODO Auto-generated constructor stub
  }

  @Override
  protected boolean lessThan(TopAlixScore arg0, TopAlixScore arg1) {
    // TODO Auto-generated method stub
    return false;
  }



}
