package io.tiler.collectors.sonarqube;

import io.tiler.collectors.sonarqube.config.Config;
import io.tiler.collectors.sonarqube.config.Metric;
import io.tiler.collectors.sonarqube.config.Server;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.logging.Logger;

import java.util.List;

public class MetricCollectionState {
  private final Logger logger;
  private final Config config;
  private boolean initialised = false;
  private final List<Server> serverConfigs;
  private List<Metric> metricConfigs;
  private Server serverConfig;
  private Metric metricConfig;
  private JsonArray servers;
  private int serverIndex;
  private int metricIndex;

  public MetricCollectionState(Logger logger, Config config) {
    this.logger = logger;
    this.config = config;
    serverConfigs = config.servers();
    servers = new JsonArray();
  }

}
