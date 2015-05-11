package io.tiler.collectors.sonarqube.config;

public class Server {
  private final String name;
  private final String host;
  private final Integer port;
  private final String path;
  private final boolean ssl;
  private final int projectLimit;

  public Server(String name, String host, Integer port, String path, boolean ssl, int projectLimit) {
    if (name == null) {
      name = host;
    }

    if (path == null) {
      path = "";
    }
    else if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }

    this.name = name;
    this.host = host;
    this.port = port;
    this.path = path;
    this.ssl = ssl;
    this.projectLimit = projectLimit;
  }

  public String name() {
    return name;
  }

  public String host() {
    return host;
  }

  public Integer port() {
    return port;
  }

  public String path() {
    return path;
  }

  public boolean ssl() {
    return ssl;
  }

  public int projectLimit() {
    return projectLimit;
  }
}
