package io.tiler.collectors.sonarqube;

import io.tiler.core.BaseCollectorVerticle;
import io.tiler.collectors.sonarqube.config.Config;
import io.tiler.collectors.sonarqube.config.ConfigFactory;
import io.tiler.collectors.sonarqube.config.Server;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class SonarQubeCollectorVerticle extends BaseCollectorVerticle {
  private Logger logger;
  private Config config;
  private EventBus eventBus;
  private DateTimeFormatter dateTimeFormatter;
  private List<HttpClient> httpClients;

  public void start() {
    logger = container.logger();
    config = new ConfigFactory().load(container.config());
    eventBus = vertx.eventBus();
    dateTimeFormatter = ISODateTimeFormat.dateTimeParser();
    httpClients = createHttpClients();

    final boolean[] isRunning = {true};

    collect(aVoid -> {
      isRunning[0] = false;
    });

    vertx.setPeriodic(config.collectionIntervalInMilliseconds(), timerID -> {
      if (isRunning[0]) {
        logger.warn("Collection aborted as previous run still executing");
        return;
      }

      isRunning[0] = true;

      collect(aVoid -> {
        isRunning[0] = false;
      });
    });

    logger.info("SonarQubeCollectorVerticle started");
  }

  private List<HttpClient> createHttpClients() {
    return config.servers()
      .stream()
      .map(server -> {
        HttpClient httpClient = vertx.createHttpClient()
          .setHost(server.host())
          .setPort(server.port())
          .setSSL(server.ssl())
          .setTryUseCompression(true);
        // Get the following error without turning keep alive off.  Looks like a vertx bug
        // SEVERE: Exception in Java verticle
        // java.nio.channels.ClosedChannelException
        httpClient.setKeepAlive(false);
        return httpClient;
      })
      .collect(Collectors.toList());
  }

  private void collect(Handler<Void> handler) {
    logger.info("Collection started");
    getProjects(servers -> {
      getProjectMetrics(servers, aVoid -> {
        transformMetrics(servers, metrics -> {
          saveMetrics(metrics);
          logger.info("Collection finished");
          handler.handle(null);
        });
      });
    });
  }

  private void getProjects(Handler<JsonArray> handler) {
    getProjects(0, new JsonArray(), handler);
  }

  private void getProjects(int serverIndex, JsonArray servers, Handler<JsonArray> handler) {
    if (serverIndex >= config.servers().size()) {
      handler.handle(servers);
      return;
    }

    Server serverConfig = config.servers().get(serverIndex);

    httpClients.get(serverIndex).getNow(serverConfig.path() + "/api/projects/index?format=json", response -> {
      response.bodyHandler(body -> {
        JsonArray projects = new JsonArray(body.toString());
        logger.info("Received " + projects.size() + " projects");
        int projectLimit = serverConfig.projectLimit();
        logger.info("Project limit set to " + projectLimit);

        if (projectLimit > 0) {
          projects = new JsonArray(projects.toList().subList(0, Math.min(projects.size(), projectLimit)));
          logger.info("There are " + projects.size() + " projects after limiting");
        }

        JsonObject server = new JsonObject()
          .putString("name", serverConfig.name())
          .putArray("projects", projects);

        servers.addObject(server);
        getProjects(serverIndex + 1, servers, handler);
      });
    });
  }

  private void getProjectMetrics(JsonArray servers, Handler<Void> handler) {
    getProjectMetrics(0, 0, servers, handler);
  }

  private void getProjectMetrics(int serverIndex, int projectIndex, JsonArray servers, Handler<Void> handler) {
    if (serverIndex >= servers.size()) {
      handler.handle(null);
      return;
    }

    JsonObject server = servers.get(serverIndex);
    Server serverConfig = config.servers().get(serverIndex);
    JsonArray projects = server.getArray("projects");

    if (projectIndex >= projects.size()) {
      getProjectMetrics(serverIndex + 1, 0, servers, handler);
      return;
    }

    JsonObject project = projects.get(projectIndex);
    String projectKey = project.getString("k");
    logger.info("Getting metrics for " + projectKey + " project");

    StringJoiner metricKeysBuilder = new StringJoiner(",");
    HashSet<String> metricKeySet = new HashSet<>();

    serverConfig.metrics().forEach(metricConfig -> {
      metricKeySet.addAll(metricConfig.sonarQubeMetricKeys());
    });

    String metricKeys = String.join(",", metricKeySet);
    String requestUri = serverConfig.path() + "/api/timemachine?resource=" + projectKey + "&metrics=" + metricKeys;

    httpClients.get(serverIndex).getNow(requestUri, response -> {
      response.bodyHandler(body -> {
        logger.info("Received metrics for " + projectKey + " project");
        JsonArray timeMachine = new JsonArray(body.toString());

        if (timeMachine.size() != 1) {
          logger.warn("Unexpected length of " + timeMachine.size() + "for time machine response ");
        } else {
          JsonObject metrics = timeMachine.get(0);
          project.putObject("metrics", metrics);
        }

        getProjectMetrics(serverIndex, projectIndex + 1, servers, handler);
      });
    });
  }

  private void transformMetrics(JsonArray servers, Handler<JsonArray> handler) {
    logger.info("Transforming metrics");
    HashMap<String, JsonObject> newMetricMap = new HashMap<>();
    long metricTimestamp = currentTimeInMicroseconds();

    for (int serverIndex = 0, serverCount = config.servers().size(); serverIndex < serverCount; serverIndex++) {
      Server serverConfig = config.servers().get(serverIndex);
      JsonObject server = servers.get(serverIndex);
      String serverName = server.getString("name");

      HashMap<String, ArrayList<JsonObject>> metricKeyToMetricsMap = new HashMap<>();

      serverConfig.metrics().forEach(metricConfig -> {
        String metricName = config.getFullMetricName(metricConfig);

        JsonObject metric = newMetricMap.get(metricName);

        if (metric == null) {
          metric = new JsonObject()
            .putString("name", metricName)
            .putArray("points", new JsonArray())
            .putNumber("timestamp", metricTimestamp);
          newMetricMap.put(metricName, metric);
        }

        List<String> metricKeys = metricConfig.sonarQubeMetricKeys();

        for (int metricKeyIndex = 0, metricKeyCount = metricKeys.size(); metricKeyIndex < metricKeyCount; metricKeyIndex++) {
          String metricKey = metricKeys.get(metricKeyIndex);
          ArrayList<JsonObject> newMetrics = metricKeyToMetricsMap.get(metricKey);

          if (newMetrics == null) {
            newMetrics = new ArrayList<>();
            metricKeyToMetricsMap.put(metricKey, newMetrics);
          }

          newMetrics.add(metric);
        }
      });

      server.getArray("projects").forEach(projectObject -> {
        JsonObject project = (JsonObject) projectObject;
        String projectKey = project.getString("k");
        String projectName = project.getString("nm");
        JsonObject metrics = project.getObject("metrics");

        JsonArray columns = metrics.getArray("cols");
        JsonArray cells = metrics.getArray("cells");

        cells.forEach(cellObject -> {
          JsonObject cell = (JsonObject) cellObject;
          long pointTime = getTimestampInMicrosecondsFromISODateTime(cell.getString("d"));
          JsonArray values = cell.getArray("v");

          for (int columnIndex = 0, columnCount = columns.size(); columnIndex < columnCount; columnIndex++) {
            Number value = values.get(columnIndex);

            if (value != null) {
              JsonObject column = columns.get(columnIndex);
              String metricKey = column.getString("metric");

              ArrayList<JsonObject> newMetrics = metricKeyToMetricsMap.get(metricKey);

              for (int metricIndex = 0, metricCount = newMetrics.size(); metricIndex < metricCount; metricIndex++) {
                JsonArray newPoints = newMetrics.get(metricIndex).getArray("points");
                newPoints.addObject(new JsonObject()
                  .putNumber("time", pointTime)
                  .putString("serverName", serverName)
                  .putString("projectKey", projectKey)
                  .putString("projectName", projectName)
                  .putNumber("value", value));
              }
            }
          }
        });
      });
    }

    JsonArray newMetrics = new JsonArray();
    newMetricMap.values().forEach(newMetrics::addObject);

    handler.handle(newMetrics);
  }

  private long getTimestampInMicrosecondsFromISODateTime(String isoDateTime) {
    return dateTimeFormatter.parseDateTime(isoDateTime).getMillis() * 1000;
  }
}
