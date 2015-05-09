package io.tiler.collectors.sonarqube;

import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Verticle;

import java.util.HashMap;
import java.util.List;

public class SonarQubeCollectorVerticle extends Verticle {
  private Logger logger;
  private JsonObject config;
  private EventBus eventBus;
  private DateTimeFormatter dateTimeFormatter;
  private HttpClient httpClient;

  public void start() {
    logger = container.logger();
    config = container.config();
    eventBus = vertx.eventBus();
    dateTimeFormatter = ISODateTimeFormat.dateTimeParser();
    httpClient = vertx.createHttpClient()
      .setHost(getSonarQubeHost())
      .setPort(getSonarQubePort())
      .setSSL(getSonarQubeSsl())
      .setTryUseCompression(true);
    // Get the following error without turning keep alive off.  Looks like a vertx bug
    // SEVERE: Exception in Java verticle
    // java.nio.channels.ClosedChannelException
    httpClient.setKeepAlive(false);

    final boolean[] isRunning = {true};

    collect(aVoid -> {
      isRunning[0] = false;
    });

    vertx.setPeriodic(3600000, aLong -> {
      if (isRunning[0]) {
        logger.info("Collection aborted as previous run still executing");
        return;
      }

      isRunning[0] = true;

      collect(aVoid -> {
        isRunning[0] = false;
      });
    });

    logger.info("SonarQubeCollectorVerticle started");
  }

  private boolean getSonarQubeSsl() {
    return config.getBoolean("ssl", false);
  }

  private Integer getSonarQubePort() {
    return config.getInteger("port", 9000);
  }

  private String getSonarQubeHost() {
    return config.getString("host", "localhost");
  }

  private Integer getProjectLimit() {
    return config.getInteger("projectLimit", 10);
  }

  private void collect(Handler<Void> handler) {
    logger.info("Collection started");
    getProjects(getProjectLimit(), projects -> {
      getProjectMetrics(projects, 0, aVoid -> {
        transformMetrics(projects, metrics -> {
          publishNewMetrics(metrics, aVoid3 -> {
            logger.info("Collection finished");
            handler.handle(null);
          });
        });
      });
    });
  }

  private void getProjects(int projectLimit, Handler<JsonArray> handler) {
    httpClient.getNow("/api/projects/index?format=json", response -> {
      response.bodyHandler(body -> {
        logger.info("Received project list " + body);
        JsonArray projects = new JsonArray(body.toString());
        logger.info("Received " + projects.size() + " projects");
        logger.info("Project limit set to " + projectLimit);

        List projectList = projects.toList();
        int projectCount = projectList.size();

        while (projectCount > projectLimit) {
          projectCount--;
          projectList.remove(projectCount);
        }

        projects = new JsonArray(projectList);
        logger.info("There are " + projects.size() + " projects after limiting");

        handler.handle(projects);
      });
    });
  }

  private void getProjectMetrics(JsonArray projects, int projectIndex, Handler<Void> handler) {
    if (projectIndex >= projects.size()) {
      handler.handle(null);
      return;
    }

    JsonObject project = projects.get(projectIndex);
    String projectKey = project.getString("k");
    logger.info("Getting metrics for " + projectKey + " project");

    httpClient.getNow("/api/timemachine?resource=" + projectKey + "&metrics=coverage", response -> {
      response.bodyHandler(body -> {
        logger.info("Received metrics for " + projectKey + " project " + body);
        JsonArray timemachine = new JsonArray(body.toString());

        if (timemachine.size() != 1) {
          logger.warn("Unexpected length of " + timemachine.size() + "for timemachine response ");
        } else {
          JsonObject metrics = timemachine.get(0);
          project.putObject("metrics", metrics);
        }

        getProjectMetrics(projects, projectIndex + 1, handler);
      });
    });
  }

  private void transformMetrics(JsonArray projects, Handler<JsonArray> handler) {
    logger.info("Transforming metrics");
    HashMap<String, JsonObject> newMetricMap = new HashMap<>();
    long metricTimestamp = getCurrentTimestampInMicroseconds();

    for (int projectIndex = 0, projectCount = projects.size(); projectIndex < projectCount; projectIndex++) {
      JsonObject project = projects.get(projectIndex);
      String projectKey = project.getString("k");
      String projectName = project.getString("nm");
      JsonObject metrics = project.getObject("metrics");

      JsonArray columns = metrics.getArray("cols");
      JsonArray cells = metrics.getArray("cells");

      for (int cellIndex = 0, cellCount = cells.size(); cellIndex < cellCount; cellIndex ++) {
        JsonObject cell = cells.get(cellIndex);
        long pointTime = getTimestampInMicrosecondsFromISODateTime(cell.getString("d"));
        JsonArray values = cell.getArray("v");

        for (int columnIndex = 0, columnCount = columns.size(); columnIndex < columnCount; columnIndex++) {
          JsonObject column = columns.get(columnIndex);
          String newMetricName = "ci.sonarqube." + column.getString("metric");

          JsonObject newMetric = newMetricMap.get(newMetricName);

          if (newMetric == null) {
            newMetric = new JsonObject()
              .putString("name", newMetricName)
              .putArray("points", new JsonArray())
              .putNumber("timestamp", metricTimestamp);
            newMetricMap.put(newMetricName, newMetric);
          }

          JsonArray newPoints = newMetric.getArray("points");
          newPoints.addObject(new JsonObject()
            .putNumber("time", pointTime)
            .putString("projectKey", projectKey)
            .putString("projectName", projectName)
            .putNumber("value", values.get(columnIndex)));
        }
      }
    }

    JsonArray newMetrics = new JsonArray();

    for (JsonObject newMetric : newMetricMap.values()) {
      newMetrics.addObject(newMetric);
    }

    handler.handle(newMetrics);
  }

  private long getTimestampInMicrosecondsFromISODateTime(String isoDateTime) {
    return dateTimeFormatter.parseDateTime(isoDateTime).getMillis() * 1000;
  }

  private long getCurrentTimestampInMicroseconds() {
    return System.currentTimeMillis() * 1000;
  }

  private void publishNewMetrics(JsonArray metrics, Handler<Void> handler) {
    logger.info("Publishing metrics to event bus");
    logger.info("New metrics " + metrics);
    JsonObject message = new JsonObject()
      .putArray("metrics", metrics);
    eventBus.publish("io.squarely.vertxspike.metrics", message);
    handler.handle(null);
  }
}
