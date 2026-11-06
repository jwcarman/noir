package com.example.api;

/** A counter that starts at zero. */
public class Counter {
  private int count;

  /** Adds one to the count. */
  public void increment() {
    count++;
  }

  /** Returns the current count. */
  public int get() {
    return count;
  }

  /** Takes one from the count. */
  public void decrement() {
    count--;
  }
}
