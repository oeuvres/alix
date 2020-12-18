package alix.util;

import java.util.Queue;

public class TestRoll
{
  static public void simple()
  {
    Queue<Integer> q = new Roll<Integer>(5);

    System.out.println("add(0...9) and show rolling effect");
    for (int i = 0; i < 10; i++) {
      q.add(i);
      System.out.println("add(" + i + ")"+q);
    }

    // To remove the head of queue. 
    int removed = q.remove();
    System.out.println("remove(), oldest inserted element: " + removed);
    System.out.println(q);

    // To view the head of queue 
    int head = q.peek();
    System.out.println("peek(), oldest added element: " + head);

    int size = q.size();
    System.out.println("Size of queue: " + size);
  }
  static public void main(String[] args)
  {
    simple();
  }
}
