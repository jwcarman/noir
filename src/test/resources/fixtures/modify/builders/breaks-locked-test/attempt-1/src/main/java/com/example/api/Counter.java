package com.example.api;

/** A counter that starts at zero. */
public class Counter {
  private int count;

  /** Adds one to the count, but never above one. */
  public void increment() {
    if (count < 1) {
      count++;
    }
  }

  /** Returns the current count. */
  public int get() {
    return count;
  }

  /** Takes one from the count. */
  public void decrement() {
    count--;
  }

  /** Whether the count is above {@code limit}. */
  public boolean isAbove(int limit) {
    return count > limit;
  }

  /** Returns the count doubled. */
  public int doubled() {
    return count * 2;
  }

  /** Returns the count halved, rounding toward zero. */
  public int halved() {
    return count / 2;
  }

  /** Sets the count back to zero. */
  public void reset() {
    count = 0;
  }
}
