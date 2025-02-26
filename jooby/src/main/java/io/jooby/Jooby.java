/**
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby;

import com.typesafe.config.Config;
import io.jooby.internal.RouterImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Provider;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Spliterator;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.StreamSupport.stream;

/**
 * <h1>Welcome to Jooby!</h1>
 *
 * <p>Hello World!</p>
 * <pre>{@code
 *
 * public class App extends Jooby {
 *
 *   {
 *     get("/", ctx -> "Hello World!");
 *   }
 *
 *   public static void main(String[] args) {
 *     runApp(args, App::new);
 *   }
 * }
 *
 * }</pre>
 *
 * More documentation at <a href="https://jooby.io">jooby.io</a>
 *
 * @since 2.0.0
 * @author edgar
 */
public class Jooby implements Router, Registry {

  static final String BASE_PACKAGE = "application.package";

  static final String APP_NAME = "___app_name__";

  private static final String JOOBY_RUN_HOOK = "___jooby_run_hook__";

  private final transient AtomicBoolean started = new AtomicBoolean(true);

  private RouterImpl router;

  private ExecutionMode mode;

  private Path tmpdir;

  private List<SneakyThrows.Runnable> readyCallbacks;

  private List<SneakyThrows.Runnable> startingCallbacks;

  private LinkedList<AutoCloseable> stopCallbacks;

  private Environment env;

  private Registry registry;

  private ServerOptions serverOptions;

  private EnvironmentOptions environmentOptions;

  private boolean lateInit;

  private String name;

  private String version;

  /**
   * Creates a new Jooby instance.
   */
  public Jooby() {
    ClassLoader classLoader = getClass().getClassLoader();
    environmentOptions = new EnvironmentOptions().setClassLoader(classLoader);
    router = new RouterImpl(classLoader);
  }

  /**
   * Server options or <code>null</code>.
   *
   * @return Server options or <code>null</code>.
   */
  public @Nullable ServerOptions getServerOptions() {
    return serverOptions;
  }

  /**
   * Set server options.
   *
   * @param serverOptions Server options.
   * @return This application.
   */
  public @Nonnull Jooby setServerOptions(@Nonnull ServerOptions serverOptions) {
    this.serverOptions = serverOptions;
    return this;
  }

  @Nonnull @Override public RouterOptions getRouterOptions() {
    return router.getRouterOptions();
  }

  @Nonnull @Override public Jooby setRouterOptions(@Nonnull RouterOptions options) {
    router.setRouterOptions(options);
    return this;
  }

  /**
   * Application environment. If none was set, environment is initialized
   * using {@link Environment#loadEnvironment(EnvironmentOptions)}.
   *
   * @return Application environment.
   */
  public @Nonnull Environment getEnvironment() {
    if (env == null) {
      env = Environment.loadEnvironment(environmentOptions);
    }
    return env;
  }

  /**
   * Application class loader.
   *
   * @return Application class loader.
   */
  public @Nonnull ClassLoader getClassLoader() {
    return env == null ? environmentOptions.getClassLoader() : env.getClassLoader();
  }

  /**
   * Application configuration. It is a shortcut for {@link Environment#getConfig()}.
   *
   * @return Application config.
   */
  public @Nonnull Config getConfig() {
    return getEnvironment().getConfig();
  }

  /**
   * Set application environment.
   *
   * @param environment Application environment.
   * @return This application.
   */
  public @Nonnull Jooby setEnvironment(@Nonnull Environment environment) {
    this.env = environment;
    return this;
  }

  /**
   * Set environment options and initialize/overrides the environment.
   *
   * @param options Environment options.
   * @return New environment.
   */
  public @Nonnull Environment setEnvironmentOptions(@Nonnull EnvironmentOptions options) {
    this.environmentOptions = options;
    this.env = Environment.loadEnvironment(
        options.setClassLoader(options.getClassLoader(getClass().getClassLoader())));
    return this.env;
  }

  /**
   * Event fired before starting router and web-server. Non-lateinit extension are installed at
   * this stage.
   *
   * @param body Start body.
   * @return This application.
   */
  public @Nonnull Jooby onStarting(@Nonnull SneakyThrows.Runnable body) {
    if (startingCallbacks == null) {
      startingCallbacks = new ArrayList<>();
    }
    startingCallbacks.add(body);
    return this;
  }

  /**
   * Event is fire once all components has been initialized, for example router and web-server
   * are up and running, extension installed, etc...
   *
   * @param body Start body.
   * @return This application.
   */
  public @Nonnull Jooby onStarted(@Nonnull SneakyThrows.Runnable body) {
    if (readyCallbacks == null) {
      readyCallbacks = new ArrayList<>();
    }
    readyCallbacks.add(body);
    return this;
  }

  /**
   * Stop event is fire at application shutdown time. Useful to execute cleanup task, free
   * resources, etc...
   *
   * @param body Stop body.
   * @return This application.
   */
  public @Nonnull Jooby onStop(@Nonnull AutoCloseable body) {
    if (stopCallbacks == null) {
      stopCallbacks = new LinkedList<>();
    }
    stopCallbacks.addFirst(body);
    return this;
  }

  @Nonnull @Override public Jooby setContextPath(@Nonnull String basePath) {
    router.setContextPath(basePath);
    return this;
  }

  @Nonnull @Override public String getContextPath() {
    return router.getContextPath();
  }

  @Nonnull @Override
  public Jooby use(@Nonnull Router router) {
    this.router.use(router);
    return this;
  }

  @Nonnull @Override
  public Jooby use(@Nonnull Predicate<Context> predicate, @Nonnull Router router) {
    this.router.use(predicate, router);
    return this;
  }

  @Nonnull @Override public Jooby use(@Nonnull String path, @Nonnull Router router) {
    this.router.use(path, router);
    return this;
  }

  @Nonnull @Override public Jooby mvc(@Nonnull Object router) {
    Provider provider = () -> router;
    return mvc(router.getClass(), provider);
  }

  @Nonnull @Override public Jooby mvc(@Nonnull Class router) {
    return mvc(router, () -> require(router));
  }

  @Nonnull @Override
  public <T> Jooby mvc(@Nonnull Class<T> router, @Nonnull Provider<T> provider) {
    try {
      ServiceLoader<MvcFactory> modules = ServiceLoader.load(MvcFactory.class);
      MvcFactory module = stream(modules.spliterator(), false)
          .filter(it -> it.supports(router))
          .findFirst()
          .orElseGet(() ->
              /** Make happy IDE incremental build: */
              mvcReflectionFallback(router, getClassLoader())
                  .orElseThrow(() -> Usage.mvcRouterNotFound(router))
          );
      Extension extension = module.create(provider);
      extension.install(this);
      return this;
    } catch (Exception x) {
      throw SneakyThrows.propagate(x);
    }
  }

  @Nonnull @Override
  public Route ws(@Nonnull String pattern, @Nonnull WebSocket.Initializer handler) {
    return router.ws(pattern, handler);
  }

  @Nonnull @Override public List<Route> getRoutes() {
    return router.getRoutes();
  }

  @Nonnull @Override public Jooby error(@Nonnull ErrorHandler handler) {
    router.error(handler);
    return this;
  }

  @Nonnull @Override public Jooby decorator(@Nonnull Route.Decorator decorator) {
    router.decorator(decorator);
    return this;
  }

  @Nonnull @Override public Jooby before(@Nonnull Route.Before before) {
    router.before(before);
    return this;
  }

  @Nonnull @Override public Jooby after(@Nonnull Route.After after) {
    router.after(after);
    return this;
  }

  @Nonnull @Override public Jooby encoder(@Nonnull MessageEncoder encoder) {
    router.encoder(encoder);
    return this;
  }

  @Nonnull @Override public Jooby decoder(@Nonnull MediaType contentType, @Nonnull
      MessageDecoder decoder) {
    router.decoder(contentType, decoder);
    return this;
  }

  @Nonnull @Override
  public Jooby encoder(@Nonnull MediaType contentType, @Nonnull MessageEncoder encoder) {
    router.encoder(contentType, encoder);
    return this;
  }

  /**
   * Install extension module.
   *
   * @param extension Extension module.
   * @return This application.
   */
  @Nonnull public Jooby install(@Nonnull Extension extension) {
    if (lateInit || extension.lateinit()) {
      onStarting(() -> extension.install(this));
    } else {
      try {
        extension.install(this);
      } catch (Exception x) {
        throw SneakyThrows.propagate(x);
      }
    }
    return this;
  }

  @Nonnull @Override public Jooby dispatch(@Nonnull Runnable body) {
    router.dispatch(body);
    return this;
  }

  @Nonnull @Override public Jooby dispatch(@Nonnull Executor executor, @Nonnull Runnable action) {
    router.dispatch(executor, action);
    return this;
  }

  @Nonnull @Override public Jooby path(@Nonnull String pattern, @Nonnull Runnable action) {
    router.path(pattern, action);
    return this;
  }

  @Nonnull @Override public Jooby route(@Nonnull Runnable action) {
    router.route(action);
    return this;
  }

  @Nonnull @Override
  public Route route(@Nonnull String method, @Nonnull String pattern,
      @Nonnull Route.Handler handler) {
    return router.route(method, pattern, handler);
  }

  @Nonnull @Override public Match match(@Nonnull Context ctx) {
    return router.match(ctx);
  }

  @Nonnull @Override
  public Jooby errorCode(@Nonnull Class<? extends Throwable> type,
      @Nonnull StatusCode statusCode) {
    router.errorCode(type, statusCode);
    return this;
  }

  @Nonnull @Override public StatusCode errorCode(@Nonnull Throwable cause) {
    return router.errorCode(cause);
  }

  @Nonnull @Override public Executor getWorker() {
    return router.getWorker();
  }

  @Nonnull @Override public Jooby setWorker(@Nonnull Executor worker) {
    this.router.setWorker(worker);
    if (worker instanceof ExecutorService) {
      onStop(((ExecutorService) worker)::shutdown);
    }
    return this;
  }

  @Nonnull @Override public Jooby setDefaultWorker(@Nonnull Executor worker) {
    this.router.setDefaultWorker(worker);
    return this;
  }

  @Nonnull @Override public Logger getLog() {
    return LoggerFactory.getLogger(getClass());
  }

  @Nonnull @Override public Jooby responseHandler(ResponseHandler handler) {
    router.responseHandler(handler);
    return this;
  }

  @Nonnull @Override public ErrorHandler getErrorHandler() {
    return router.getErrorHandler();
  }

  @Nonnull @Override public Path getTmpdir() {
    return tmpdir;
  }

  /**
   * Set application temporary directory.
   *
   * @param tmpdir Temp directory.
   * @return This application.
   */
  public @Nonnull Jooby setTmpdir(@Nonnull Path tmpdir) {
    this.tmpdir = tmpdir;
    return this;
  }

  /**
   * Application execution mode.
   *
   * @return Application execution mode.
   */
  public @Nonnull ExecutionMode getExecutionMode() {
    return mode == null ? ExecutionMode.DEFAULT : mode;
  }

  /**
   * Set application execution mode.
   *
   * @param mode Application execution mode.
   * @return This application.
   */
  public @Nonnull Jooby setExecutionMode(@Nonnull ExecutionMode mode) {
    this.mode = mode;
    return this;
  }

  @Nonnull @Override public Map<String, Object> getAttributes() {
    return router.getAttributes();
  }

  @Nonnull @Override public Jooby attribute(@Nonnull String key, @Nonnull Object value) {
    router.attribute(key, value);
    return this;
  }

  @Nonnull @Override public <T> T attribute(@Nonnull String key) {
    return router.attribute(key);
  }

  @Nonnull @Override public <T> T require(@Nonnull Class<T> type, @Nonnull String name) {
    return require(ServiceKey.key(type, name));
  }

  @Nonnull @Override public <T> T require(@Nonnull Class<T> type) {
    return require(ServiceKey.key(type));
  }

  @Override public @Nonnull <T> T require(@Nonnull ServiceKey<T> key) {
    ServiceRegistry services = getServices();
    T service = services.getOrNull(key);
    if (service == null) {
      if (registry == null) {
        throw new RegistryException("Service not found: " + key);
      }
      String name = key.getName();
      return name == null ? registry.require(key.getType()) : registry.require(key.getType(), name);
    }
    return service;
  }

  /**
   * Set application registry.
   *
   * @param registry Application registry.
   * @return This application.
   */
  @Nonnull public Jooby registry(@Nonnull Registry registry) {
    this.registry = registry;
    return this;
  }

  @Nonnull @Override public ServiceRegistry getServices() {
    return this.router.getServices();
  }

  /**
   * Get base application package. This is the package from where application was initialized
   * or the package of a Jooby application sub-class.
   *
   * @return Base application package.
   */
  public @Nullable String getBasePackage() {
    return System.getProperty(BASE_PACKAGE);
  }

  @Nonnull @Override public SessionStore getSessionStore() {
    return router.getSessionStore();
  }

  @Nonnull @Override public Jooby setSessionStore(@Nonnull SessionStore store) {
    router.setSessionStore(store);
    return this;
  }

  @Nonnull @Override public Jooby executor(@Nonnull String name, @Nonnull Executor executor) {
    if (executor instanceof ExecutorService) {
      onStop(((ExecutorService) executor)::shutdown);
    }
    router.executor(name, executor);
    return this;
  }

  @Nonnull @Override public String getFlashCookie() {
    return router.getFlashCookie();
  }

  @Nonnull @Override public Jooby setFlashCookie(@Nonnull String name) {
    router.setFlashCookie(name);
    return this;
  }

  @Nonnull @Override public Jooby converter(@Nonnull ValueConverter converter) {
    router.converter(converter);
    return this;
  }

  @Nonnull @Override public Router converter(@Nonnull BeanConverter converter) {
    router.converter(converter);
    return this;
  }

  @Nonnull @Override public List<ValueConverter> getConverters() {
    return router.getConverters();
  }

  @Nonnull @Override public List<BeanConverter> getBeanConverters() {
    return router.getBeanConverters();
  }

  /**
   * Start application, find a web server, deploy application, start router, extension modules,
   * etc..
   *
   * @return Server.
   */
  public @Nonnull Server start() {
    List<Server> servers = stream(
        spliteratorUnknownSize(
            ServiceLoader.load(Server.class).iterator(),
            Spliterator.ORDERED),
        false)
        .collect(Collectors.toList());
    if (servers.size() == 0) {
      throw new IllegalStateException("Server not found.");
    }
    if (servers.size() > 1) {
      List<String> names = servers.stream()
          .map(it -> it.getClass().getSimpleName().toLowerCase())
          .collect(Collectors.toList());
      getLog().warn("Multiple servers found {}. Using: {}", names, names.get(0));
    }
    Server server = servers.get(0);
    try {
      if (serverOptions == null) {
        serverOptions = ServerOptions.from(getEnvironment().getConfig()).orElse(null);
      }
      if (serverOptions != null) {
        serverOptions.setServer(server.getClass().getSimpleName().toLowerCase());
        server.setOptions(serverOptions);
      }
      return server.start(this);
    } catch (Throwable x) {
      Logger log = getLog();
      log.error("Application startup resulted in exception", x);
      try {
        server.stop();
      } catch (Throwable stopx) {
        log.info("Server stop resulted in exception", stopx);
      }
      // rethrow
      throw SneakyThrows.propagate(x);
    }
  }

  /**
   * Call back method that indicates application was deploy it in the given server.
   *
   * @param server Server.
   * @return This application.
   */
  public @Nonnull Jooby start(@Nonnull Server server) {
    Environment env = getEnvironment();
    if (tmpdir == null) {
      tmpdir = Paths.get(env.getConfig().getString("application.tmpdir")).toAbsolutePath();
    }

    /** Start router: */
    ensureTmpdir(tmpdir);

    if (mode == null) {
      mode = ExecutionMode.DEFAULT;
    }

    joobyRunHook(getClass().getClassLoader(), server);

    this.startingCallbacks = fire(this.startingCallbacks);

    router.start(this);

    return this;
  }

  /**
   * Callback method that indicates application was successfully started it and listening for
   * connections.
   *
   * @param server Server.
   * @return This application.
   */
  public @Nonnull Jooby ready(@Nonnull Server server) {
    Logger log = getLog();

    this.readyCallbacks = fire(this.readyCallbacks);

    log.info("{} started with:", System.getProperty(APP_NAME, getClass().getSimpleName()));

    log.info("    PID: {}", System.getProperty("PID"));
    log.info("    {}", server.getOptions());
    if (log.isDebugEnabled()) {
      log.debug("    env: {}", env);
    } else {
      log.info("    env: {}", env.getActiveNames());
    }
    log.info("    execution model: {}", mode.name().toLowerCase());
    log.info("    user: {}", System.getProperty("user.name"));
    log.info("    app dir: {}", System.getProperty("user.dir"));
    log.info("    tmp dir: {}", tmpdir);

    log.info("routes: \n\n{}\n\nlistening on:\n  http://localhost:{}{}\n", router,
        server.getOptions().getPort(),
        router.getContextPath());
    return this;
  }

  /**
   * Stop application, fire the stop event to cleanup resources.
   *
   * This method is usually invoked by {@link Server#stop()} using a shutdown hook.
   *
   * The next example shows how to successfully stop the web server and application:
   *
   * <pre>{@code
   *   Jooby app = new Jooby();
   *
   *   Server server = app.start();
   *
   *   ...
   *
   *   server.stop();
   * }</pre>
   *
   * @return This application.
   */
  public @Nonnull Jooby stop() {
    if (started.compareAndSet(true, false)) {
      Logger log = getLog();
      log.debug("Stopping {}", System.getProperty(APP_NAME, getClass().getSimpleName()));
      router.destroy();

      fireStop();

      log.info("Stopped {}", System.getProperty(APP_NAME, getClass().getSimpleName()));
    }
    return this;
  }

  /**
   * Force all module to be initialized lazily at application startup time (not at
   * creation/instantiation time).
   *
   * This option is present mostly for unit-test where you need to instantiated a Jooby instance
   * without running extensions.
   *
   * @param lateInit True for late init.
   * @return This application.
   */
  public Jooby setLateInit(boolean lateInit) {
    this.lateInit = lateInit;
    return this;
  }

  /**
   * Get application's name. If none set:
   *
   * - Try to get from {@link Package#getImplementationTitle()}.
   * - Otherwise fallback to class name.
   *
   * @return Application's name.
   */
  public @Nonnull String getName() {
    if (name == null) {
      name = Optional.ofNullable(getClass().getPackage())
          .map(Package::getImplementationTitle)
          .filter(Objects::nonNull)
          .orElse(getClass().getSimpleName());
    }
    return name;
  }

  /**
   * Set application name (only for description purpose).
   *
   * @param name Application's name.
   * @return This application.
   */
  public @Nonnull Jooby setName(@Nonnull String name) {
    this.name = name;
    return this;
  }

  /**
   * Get application version (description/debugging purpose only). If none set:
   *
   * - Try to get it from {@link Package#getImplementationVersion()}. This attribute is present
   * when application has been packaged (jar file).
   *
   * - Otherwise, fallback to <code>0.0.0</code>.
   *
   * @return Application version.
   */
  public @Nonnull String getVersion() {
    if (version == null) {
      version = Optional.ofNullable(getClass().getPackage())
          .map(Package::getImplementationVersion)
          .filter(Objects::nonNull)
          .orElse("0.0.0");
    }
    return version;
  }

  /**
   * Set application version.
   *
   * @param version Application's version.
   * @return This application.
   */
  public @Nonnull Jooby setVersion(@Nonnull String version) {
    this.version = version;
    return this;
  }

  @Override public String toString() {
    return getName() + ":" + getVersion();
  }

  /**
   * Setup default environment, logging (logback or log4j2) and run application.
   *
   * @param args Application arguments.
   * @param applicationType Application type.
   */
  public static void runApp(@Nonnull String[] args,
      @Nonnull Class<? extends Jooby> applicationType) {
    runApp(args, ExecutionMode.DEFAULT, applicationType);
  }

  /**
   * Setup default environment, logging (logback or log4j2) and run application.
   *
   * @param args Application arguments.
   * @param executionMode Application execution mode.
   * @param applicationType Application type.
   */
  public static void runApp(@Nonnull String[] args, @Nonnull ExecutionMode executionMode,
      @Nonnull Class<? extends Jooby> applicationType) {
    configurePackage(applicationType.getPackage());
    runApp(args, executionMode, reflectionProvider(applicationType));
  }

  /**
   * Setup default environment, logging (logback or log4j2) and run application.
   *
   * @param args Application arguments.
   * @param executionMode Application execution mode.
   * @param applicationType Application type.
   * @return Application.
   */
  public static Jooby createApp(@Nonnull String[] args, @Nonnull ExecutionMode executionMode,
      @Nonnull Class<? extends Jooby> applicationType) {
    configurePackage(applicationType.getPackage());
    return createApp(args, executionMode, reflectionProvider(applicationType));
  }

  /**
   * Setup default environment, logging (logback or log4j2) and run application.
   *
   * @param args Application arguments.
   * @param provider Application provider.
   */
  public static void runApp(@Nonnull String[] args, @Nonnull Supplier<Jooby> provider) {
    runApp(args, ExecutionMode.DEFAULT, provider);
  }

  /**
   * Setup default environment, logging (logback or log4j2) and run application.
   *
   * @param args Application arguments.
   * @param consumer Application consumer.
   */
  public static void runApp(@Nonnull String[] args, @Nonnull Consumer<Jooby> consumer) {
    configurePackage(consumer.getClass().getPackage());
    runApp(args, ExecutionMode.DEFAULT, consumerProvider(consumer));
  }

  /**
   * Setup default environment, logging (logback or log4j2) and run application.
   *
   * @param args Application arguments.
   * @param executionMode Application execution mode.
   * @param consumer Application consumer.
   */
  public static void runApp(@Nonnull String[] args, @Nonnull ExecutionMode executionMode,
      @Nonnull Consumer<Jooby> consumer) {
    configurePackage(consumer.getClass().getPackage());
    runApp(args, executionMode, consumerProvider(consumer));
  }

  /**
   * Setup default environment, logging (logback or log4j2) and run application.
   *
   * @param args Application arguments.
   * @param executionMode Application execution mode.
   * @param provider Application provider.
   */
  public static void runApp(@Nonnull String[] args, @Nonnull ExecutionMode executionMode,
      @Nonnull Supplier<Jooby> provider) {
    Jooby app = createApp(args, executionMode, provider);
    Server server = app.start();
    Config conf = app.getConfig();
    boolean join = conf.hasPath("server.join") ? conf.getBoolean("server.join") : true;
    if (join) {
      server.join();
    }
  }

  /**
   * Setup default environment, logging (logback or log4j2) and run application.
   *
   * @param args Application arguments.
   * @param executionMode Application execution mode.
   * @param provider Application provider.
   * @return Application.
   */
  public static Jooby createApp(@Nonnull String[] args, @Nonnull ExecutionMode executionMode,
      @Nonnull Supplier<Jooby> provider) {

    configurePackage(provider.getClass().getPackage());

    /** Dump command line as system properties. */
    parseArguments(args).forEach(System::setProperty);

    /** Fin application.env: */
    LogConfigurer.configure(new EnvironmentOptions().getActiveNames());

    Jooby app = provider.get();
    if (app.mode == null) {
      app.mode = executionMode;
    }
    return app;
  }

  private static void configurePackage(Package pkg) {
    if (pkg != null) {
      configurePackage(pkg.getName());
    }
  }

  private static void configurePackage(Class owner) {
    if (!owner.getName().equals("io.jooby.Jooby") && !owner.getName().equals("io.jooby.Kooby")) {
      configurePackage(owner.getPackage());
    }
  }

  private static void configurePackage(String packageName) {
    if (!packageName.equals("io.jooby")) {
      ifSystemProp(BASE_PACKAGE, (sys, key) -> {
        sys.setProperty(key, packageName);
      });
    }
  }

  private static void ifSystemProp(String name, BiConsumer<Properties, String> consumer) {
    if (System.getProperty(name) == null) {
      consumer.accept(System.getProperties(), name);
    }
  }

  static Map<String, String> parseArguments(String... args) {
    if (args == null || args.length == 0) {
      return Collections.emptyMap();
    }
    Map<String, String> conf = new LinkedHashMap<>();
    for (String arg : args) {
      int eq = arg.indexOf('=');
      if (eq > 0) {
        conf.put(arg.substring(0, eq).trim(), arg.substring(eq + 1).trim());
      } else {
        // must be the environment actives
        conf.putIfAbsent("application.env", arg);
      }
    }
    return conf;
  }

  private static void ensureTmpdir(Path tmpdir) {
    try {
      if (!Files.exists(tmpdir)) {
        Files.createDirectories(tmpdir);
      }
    } catch (IOException x) {
      throw SneakyThrows.propagate(x);
    }
  }

  private List<SneakyThrows.Runnable> fire(List<SneakyThrows.Runnable> tasks) {
    if (tasks != null) {
      Iterator<SneakyThrows.Runnable> iterator = tasks.iterator();
      while (iterator.hasNext()) {
        SneakyThrows.Runnable task = iterator.next();
        task.run();
        iterator.remove();
      }
    }
    return null;
  }

  private void fireStop() {
    if (stopCallbacks != null) {
      List<AutoCloseable> tasks = stopCallbacks;
      stopCallbacks = null;
      Iterator<AutoCloseable> iterator = tasks.iterator();
      while (iterator.hasNext()) {
        AutoCloseable task = iterator.next();
        try {
          task.close();
        } catch (Exception x) {
          getLog().error("exception found while executing onStop:", x);
        }
        iterator.remove();
      }
    }
  }

  private static Supplier<Jooby> consumerProvider(Consumer<Jooby> consumer) {
    configurePackage(consumer.getClass());
    return () -> {
      Jooby app = new Jooby();
      consumer.accept(app);
      return app;
    };
  }

  private static Supplier<Jooby> reflectionProvider(
      @Nonnull Class<? extends Jooby> applicationType) {
    return () ->
        (Jooby) Stream.of(applicationType.getDeclaredConstructors())
            .filter(it -> it.getParameterCount() == 0)
            .findFirst()
            .map(SneakyThrows.throwingFunction(c -> c.newInstance()))
            .orElseThrow(() -> new IllegalArgumentException(
                "Default constructor for: " + applicationType.getName()));
  }

  /**
   * Check if we are running from joobyRun and invoke a hook class passing the web server.
   * JoobyRun intercept the server and use it for doing hotswap.
   */
  private void joobyRunHook(ClassLoader loader, Server server) {
    if (loader.getClass().getName().equals("org.jboss.modules.ModuleClassLoader")) {
      String hookClassname = System.getProperty(JOOBY_RUN_HOOK);
      System.setProperty(JOOBY_RUN_HOOK, "");
      if (hookClassname != null && hookClassname.length() > 0) {
        try {
          Class serverRefClass = loader.loadClass(hookClassname);
          Constructor constructor = serverRefClass.getDeclaredConstructor();
          Consumer<Server> consumer = (Consumer<Server>) constructor.newInstance();
          consumer.accept(server);
        } catch (Exception x) {
          throw SneakyThrows.propagate(x);
        }
      }
    }
  }

  /**
   * This method exists to integrate IDE incremental build with MVC annotation processor. It
   * fallback to reflection to lookup for a generated mvc factory.
   *
   * @param source Controller class.
   * @param classLoader Class loader.
   * @return Mvc factory.
   */
  private Optional<MvcFactory> mvcReflectionFallback(Class source, ClassLoader classLoader) {
    try {
      String moduleName = source.getName() + "$Factory";
      Class<?> moduleType = classLoader.loadClass(moduleName);
      Constructor<?> constructor = moduleType.getDeclaredConstructor();
      getLog().debug("Loading mvc using reflection: " + source);
      return Optional.of((MvcFactory) constructor.newInstance());
    } catch (Exception x) {
      return Optional.empty();
    }
  }
}
