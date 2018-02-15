package com.http.examples;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import com.opencsv.CSVWriter;
import io.airlift.airline.Cli;
import io.airlift.airline.Help;
import io.airlift.airline.Option;
import io.airlift.airline.OptionType;
import okhttp3.*;
import org.apache.commons.text.RandomStringGenerator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.eclipse.jetty.util.ssl.SslContextFactory.TRUST_ALL_CERTS;

/** @author Stephen Durfey */
public abstract class AbstractBenchmark implements Runnable {

  @Option(
    type = OptionType.COMMAND,
    name = {"-u", "--url"},
    description = "url to hit for http tests"
  )
  public String url;

  @Option(
    type = OptionType.COMMAND,
    name = {"-h2", "http2"},
    description = "indicates to use http2"
  )
  public boolean http2;

  @Option(
    type = OptionType.COMMAND,
    name = {"-o", "--output"},
    description = "file to write results into"
  )
  public String resultsOutputDir;

  @Option(
    type = OptionType.COMMAND,
    name = {"-r", "--repetitions"},
    description = "tells command how many times to repeat the benchmark"
  )
  public int repetitions;

  @Option(
    type = OptionType.COMMAND,
    name = {"-t", "--threads"},
    description = "number of threads to execute in parallel"
  )
  public int threads;

  @Option(
    type = OptionType.COMMAND,
    name = {"-v", "--size"},
    description = "size of each entity during the upload"
  )
  public int payloadSize;

  @Option(
    type = OptionType.COMMAND,
    name = {"-e", "--entities"},
    description =
        "number of entities to upload; default uploads in the following increments: 1, 10, 100, 1000, 5000"
  )
  public int numEntities;

  @Option(
    type = OptionType.COMMAND,
    name = {"-b", "--bearer"},
    description = "the bearer token set in the Authorization header"
  )
  public String BEARER_TOKEN_LOCATION;

  protected String BEARER_TOKEN;

  Random random = new Random();
  RandomStringGenerator generator =
      new RandomStringGenerator.Builder()
          .withinRange('a', 'z')
          .usingRandom(random::nextInt)
          .build();

  protected MetricRegistry registry = new MetricRegistry();
  private List<Integer> entityCounts = Arrays.asList(1, 10, 100, 1000, 5000);
  private static final List<Protocol> HTTP1 = Arrays.asList(Protocol.HTTP_1_1);
  private static final List<Protocol> HTTP2 = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1);

  public static class Action implements Runnable {

    private final ActionOperator action;
    private final List<Protocol> protocols;
    private final String url;
    private final String metricName;
    private final int numEntities;
    private final int payloadSize;

    public Action(
        ActionOperator action,
        List<Protocol> protocols,
        String url,
        String metricName,
        int numEntities,
        int payloadSize) {
      this.action = action;
      this.protocols = protocols;
      this.url = url;
      this.metricName = metricName;
      this.numEntities = numEntities;
      this.payloadSize = payloadSize;
    }

    @Override
    public void run() {
      try {
        action.run(protocols, url, metricName, numEntities, payloadSize);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public interface ActionOperator {
    void run(
        List<Protocol> protocols, String url, String metricName, int numEntities, int payloadSize)
        throws Exception;
  }

  public abstract Action getAction(
      List<Protocol> protocols, String url, int numEntities, int payloadSize);

  public abstract Map<String, Timer> getTimers(int numEntities);

  public static void main(String... args)
      throws ExecutionException, InterruptedException, IOException {

    Cli<Runnable> benchmark =
        Cli.<Runnable>builder("benchmark")
            .withDescription("A suite of tests to benchmark a REST service")
            .withDefaultCommand(Help.class)
            .withCommands(
                SingleMessageBenchmark.class,
                StreamMessageBenchmark.class,
                PayloadMessageBenchmark.class)
            .build();

    benchmark.parse(args).run();
  }

  public void run() {
    try {
      setBearerToken();
      ExecutorService threadPool = Executors.newFixedThreadPool(threads);

      // if numEntities was set at the command line, override the default values
      if (numEntities > 0) entityCounts = Arrays.asList(numEntities);
      int messageSize = payloadSize > 0 ? payloadSize : 1500;

      List<Future> futures = new ArrayList<>();
      IntStream.range(0, repetitions)
          .forEach(
              i ->
                  entityCounts
                      .stream()
                      .forEach(
                          entities -> {
                            if (!http2) {
                              futures.add(
                                  threadPool.submit(
                                      getAction(HTTP1, url, numEntities, messageSize)));
                            } else {
                              futures.add(
                                  threadPool.submit(
                                      getAction(HTTP2, url, numEntities, messageSize)));
                            }
                          }));

      System.out.println("number of futures: " + futures.size());
      // wait for all futures to finish before moving out to print the results
      for (final Future future : futures) {
        System.out.println("waiting for future to finish");
        future.get();
      }
      System.out.println("futures have finished");
      printResults(entityCounts);
      threadPool.shutdown();
    } catch (ExecutionException | InterruptedException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void printResults(List<Integer> entityCounts) throws IOException {
    // create CSV writer
    try (CSVWriter writer = new CSVWriter(new FileWriter(resultsOutputDir))) {
      entityCounts
          .stream()
          .forEach(
              entities -> {
                Map<String, Timer> timers = getTimers(entities);
                timers
                    .entrySet()
                    .forEach(
                        e -> writer.writeNext(getTimerLine(e.getKey(), e.getValue()).split(",")));
              });
    }
  }

  private String getTimerLine(String metricName, Timer timer) {
    Snapshot snapshot = timer.getSnapshot();
    return new StringBuilder()
        .append(metricName)
        .append(",")
        .append(timer.getCount())
        .append(",")
        .append(convertToMillis(snapshot.getMedian()))
        .append(",")
        .append(convertToMillis(snapshot.getMean()))
        .append(",")
        .append(convertToMillis(snapshot.getMin()))
        .append(",")
        .append(convertToMillis(snapshot.getMax()))
        .append(",")
        .append(convertToMillis(snapshot.get75thPercentile()))
        .append(",")
        .append(convertToMillis(snapshot.get95thPercentile()))
        .append(",")
        .append(convertToMillis(snapshot.get99thPercentile()))
        .toString();
  }

  private void setBearerToken() throws IOException {
    if (BEARER_TOKEN_LOCATION != null) {
      try (BufferedReader reader = new BufferedReader(new FileReader(BEARER_TOKEN_LOCATION))) {
        BEARER_TOKEN = reader.readLine();

      }
    }
  }

  protected OkHttpClient getHttpClient(List<Protocol> protocols, String url, String metricName)
      throws NoSuchAlgorithmException, KeyManagementException {

    // create a client to communicate over HTTP/2
    OkHttpClient.Builder builder =
        new OkHttpClient()
            .newBuilder()
            .protocols(protocols)
            .addNetworkInterceptor(new HttpInterceptor(registry.timer(metricName)))
            .hostnameVerifier((hostname, session) -> true);

    if (enableSSL(url)) {
      final SSLContext sslContext = SSLContext.getInstance("SSL");
      sslContext.init(null, TRUST_ALL_CERTS, new java.security.SecureRandom());
      // Create an ssl socket factory with our all-trusting manager
      final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

      builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) TRUST_ALL_CERTS[0]);
    }

    return builder.build();
  }

  private boolean enableSSL(String url) {
    return url.contains("https");
  }

  protected class HttpInterceptor implements Interceptor {

    private final Timer timer;

    public HttpInterceptor(Timer timer) {
      this.timer = timer;
    }

    @Override
    public Response intercept(Interceptor.Chain chain) throws IOException {
      Request request = chain.request();
      Timer.Context time = timer.time();
      try {
        return chain.proceed(request);
      } finally {
        time.stop();
      }
    }
  }

  protected class HttpCallback implements Callback {

    private final CountDownLatch latch;

    public HttpCallback(CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public void onFailure(Call call, IOException e) {
      e.printStackTrace();
    }

    @Override
    public void onResponse(Call call, Response response) throws IOException {
      latch.countDown();
      response.body().close();
    }
  }

  public long convertToMillis(long num) {
    return TimeUnit.MILLISECONDS.convert(Math.round(num), TimeUnit.NANOSECONDS);
  }

  public long convertToMillis(double num) {
    return TimeUnit.MILLISECONDS.convert(Math.round(num), TimeUnit.NANOSECONDS);
  }
}
