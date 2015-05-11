package io.tiler.collectors.sonarqube.config;

import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class ConfigFactory {
  private static final long ONE_HOUR_IN_MILLISECONDS = 60 * 60 * 1000l;

  public Config load(JsonObject config) {
    return new Config(
      getCollectionIntervalInMilliseconds(config),
      getServers(config),
      getMetricNamePrefix(config));
  }

  private long getCollectionIntervalInMilliseconds(JsonObject config) {
    return config.getLong("collectionIntervalInMilliseconds", ONE_HOUR_IN_MILLISECONDS);
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
      getServerProjectLimit(server));
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
    return server.getInteger("projectLimit", 10);
  }

  private String getMetricNamePrefix(JsonObject config) {
    return config.getString("metricNamePrefix", "sonarqube");
  }
}
