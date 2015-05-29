package io.tiler.collectors.sonarqube.config;

import java.util.List;

public class Metric {
  private final String name;
  private final List<String> sonarQubeMetricKeys;

  public Metric(String name, List<String> sonarQubeMetricKeys) {
    this.name = name;
    this.sonarQubeMetricKeys = sonarQubeMetricKeys;
  }

  public String name() {
    return name;
  }

  public List<String> sonarQubeMetricKeys() {
    return sonarQubeMetricKeys;
  }
}
