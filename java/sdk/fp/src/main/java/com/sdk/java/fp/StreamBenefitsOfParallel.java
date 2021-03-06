package com.sdk.java.fp;

import com.sdk.java.fp.offtopic.Timed;

import java.util.Arrays;
import java.util.List;

public class StreamBenefitsOfParallel {

  public static void main(String[] args) {
    final List<Integer> values = Arrays.asList(1, 2, 3, 4, 5, 7, 8, 9, 10);

    Timed.timed(() -> sum(values));
    Timed.timed(() -> sumParallel(values));
  }

  static int slowOperation(int value) {
    try {
      Thread.sleep(100);
    } catch (Exception e) {
    }
    return value * 2;
  }

  static int sum(List<Integer> values) {
    return values.stream()
        .mapToInt(Integer::intValue)
        .map(StreamBenefitsOfParallel::slowOperation)
        .sum();
  }

  static int sumParallel(List<Integer> values) {
    return values.stream()
        .parallel()
        .mapToInt(Integer::intValue)
        .map(StreamBenefitsOfParallel::slowOperation)
        .sum();
  }
}
