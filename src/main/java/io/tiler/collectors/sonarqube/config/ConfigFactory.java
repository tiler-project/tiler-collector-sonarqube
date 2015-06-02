package io.tiler.collectors.sonarqube.config;

import io.tiler.core.time.TimePeriodParser;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class ConfigFactory {
  private final TimePeriodParser timePeriodParser = new TimePeriodParser();

  public Config load(JsonObject config) {
    return new Config(
      getCollectionIntervalInMilliseconds(config),
      getServers(config),
      getMetricNamePrefix(config));
  }

  private long getCollectionIntervalInMilliseconds(JsonObject config) {
    return timePeriodParser.parseTimePeriodToMilliseconds(config.getString("collectionIntervalInMilliseconds", "1h"));
  }

  private String getMetricNamePrefix(JsonObject config) {
    return config.getString("metricNamePrefix", "sonarqube");
  }

  private List<Server> getServers(JsonObject config) {
    JsonArray servers = config.getArray("servers");
    ArrayList<Server> loadedServers = new ArrayList<>();

    if (servers == null) {
      return loadedServers;
    }

    servers.forEach(serverObject -> {
      JsonObject server = (JsonObject) serverObject;
      loadedServers.add(getServer(server));
    });

    return loadedServers;
  }

  private Server getServer(JsonObject server) {

    return new Server(
      getServerName(server),
      getServerHost(server),
      getServerPort(server),
      getServerPath(server),
      getServerSsl(server),
      getServerProjectLimit(server),
      getServerMetrics(server));
  }

  private String getServerName(JsonObject server) {
    return server.getString("name");
  }

  private boolean getServerSsl(JsonObject server) {
    return server.getBoolean("ssl", false);
  }

  private int getServerPort(JsonObject server) {
    return server.getInteger("port", 9000);
  }

  private String getServerPath(JsonObject server) {
    return server.getString("path", "");
  }

  private String getServerHost(JsonObject server) {
    return server.getString("host", "localhost");
  }

  private int getServerProjectLimit(JsonObject server) {
    return server.getInteger("projectLimit", 0);
  }

  private List<Metric> getServerMetrics(JsonObject server) {
    JsonArray metrics = server.getArray("metrics");
    ArrayList<Metric> loadedMetrics = new ArrayList<>();

    if (metrics == null) {
      return loadedMetrics;
    }

    metrics.forEach(metricObject -> {
      JsonObject metric = (JsonObject) metricObject;
      loadedMetrics.add(getMetric(metric));
    });

    return loadedMetrics;
  }

  private Metric getMetric(JsonObject metric) {
    return new Metric(
      getMetricName(metric),
      getMetricSonarQubeMetricKeys(metric));
  }

  private String getMetricName(JsonObject metric) {
    return metric.getString("name");
  }

  private List<String> getMetricSonarQubeMetricKeys(JsonObject metric) {
    ArrayList<String> items = new ArrayList<>();
    metric.getArray("sonarQubeMetricKeys").forEach(item -> {
      items.add((String) item);
    });
    return items;
  }
}
