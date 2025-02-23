package com.datadog.profiling.async;

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.utils.LibraryHelper;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * It is currently assumed that this class can be initialised early so that async-profiler's thread
 * filter captures all tracing activity, which means it must not be modified to depend on JFR, so
 * that it can be installed before tracing starts.
 */
public final class AsyncProfiler {
  private static final Logger log = LoggerFactory.getLogger(AsyncProfiler.class);

  public static final String TYPE = "async";

  private static final class Singleton {
    private static final AsyncProfiler INSTANCE = newInstance();
  }

  static AsyncProfiler newInstance() {
    AsyncProfiler instance = null;
    try {
      instance = new AsyncProfiler();
    } catch (Throwable t) {
      instance = new AsyncProfiler((Void) null);
    }
    return instance;
  }

  static AsyncProfiler newInstance(ConfigProvider configProvider) {
    AsyncProfiler instance = null;
    try {
      instance = new AsyncProfiler(configProvider);
    } catch (Throwable t) {
      instance = new AsyncProfiler((Void) null);
    }
    return instance;
  }

  private final long memleakIntervalDefault;

  private final AtomicBoolean recordingFlag = new AtomicBoolean(false);
  private final ConfigProvider configProvider;
  private final one.profiler.AsyncProfiler asyncProfiler;
  private final Set<ProfilingMode> profilingModes = EnumSet.noneOf(ProfilingMode.class);

  private AsyncProfiler() throws UnsupportedEnvironmentException {
    this(ConfigProvider.getInstance());
  }

  private AsyncProfiler(Void dummy) {
    this.configProvider = null;
    this.asyncProfiler = null;
    this.memleakIntervalDefault = 0L;
  }

  private AsyncProfiler(ConfigProvider configProvider) throws UnsupportedEnvironmentException {
    this.configProvider = configProvider;
    String libDir = configProvider.getString(ProfilingConfig.PROFILING_ASYNC_LIBPATH);
    if (libDir != null && Files.exists(Paths.get(libDir))) {
      // the library from configuration takes precedence
      asyncProfiler = one.profiler.AsyncProfiler.getInstance(libDir);
    } else {
      asyncProfiler = inferFromOsAndArch();
    }
    // TODO enable/disable events by name (e.g. datadog.ExecutionSample), not flag, so configuration
    //  can be consistent with JFR event control
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_ASYNC_ALLOC_ENABLED,
        ProfilingConfig.PROFILING_ASYNC_ALLOC_ENABLED_DEFAULT)) {
      profilingModes.add(ProfilingMode.ALLOCATION);
    }
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_ASYNC_MEMLEAK_ENABLED,
        ProfilingConfig.PROFILING_ASYNC_MEMLEAK_ENABLED_DEFAULT)) {
      profilingModes.add(ProfilingMode.MEMLEAK);
    }
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_ASYNC_CPU_ENABLED,
        ProfilingConfig.PROFILING_ASYNC_CPU_ENABLED_DEFAULT)) {
      profilingModes.add(ProfilingMode.CPU);
    }
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_ASYNC_WALL_ENABLED, getAsyncWallEnabledDefault())) {
      profilingModes.add(ProfilingMode.WALL);
    }
    try {
      // sanity test - force load async profiler to catch it not being available early
      asyncProfiler.execute("status");
    } catch (IOException e) {
      throw new UnsupportedEnvironmentException("Failed to execute status on async profiler", e);
    }

    long maxheap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    this.memleakIntervalDefault =
        maxheap <= 0 ? 1 * 1024 * 1024 : maxheap / Math.max(1, getMemleakCapacity());
  }

  public static AsyncProfiler getInstance() {
    return Singleton.INSTANCE;
  }

  private static boolean getAsyncWallEnabledDefault() {
    if (Platform.isJ9()) {
      // wallclock profiling is useless on J9 - do not automatically enable it
      return false;
    }
    return ProfilingConfig.PROFILING_ASYNC_WALL_ENABLED_DEFAULT;
  }

  private static one.profiler.AsyncProfiler inferFromOsAndArch()
      throws UnsupportedEnvironmentException {
    Arch arch = Arch.current();
    OperatingSystem os = OperatingSystem.current();
    try {
      if (os != OperatingSystem.unknown) {
        if (arch != Arch.unknown) {
          try {
            return profilerForOsAndArch(os, arch, false);
          } catch (FileNotFoundException e) {
            if (os == OperatingSystem.linux) {
              // Might be a MUSL distribution
              return profilerForOsAndArch(os, arch, true);
            }
            throw e;
          }
        }
      }
    } catch (Throwable t) {
      if (log.isDebugEnabled()) {
        log.info(
            "Unable to instantiate async profiler for the detected environment: arch={}, os={}",
            arch,
            os,
            t);
      } else {
        log.info(
            "Unable to instantiate async profiler for the detected environment: arch={}, os={}, cause={}",
            arch,
            os,
            t.getMessage());
      }
      throw new UnsupportedEnvironmentException(
          String.format(
              "Unable to instantiate async profiler for the detected environment: arch=%s, os=%s",
              arch, os),
          t);
    }
    throw new UnsupportedEnvironmentException(
        String.format(
            "Unable to instantiate async profiler for the detected environment: arch=%s, os=%s",
            arch, os));
  }

  void addCurrentThread() {
    if (asyncProfiler != null) {
      asyncProfiler.addThread(Thread.currentThread());
    }
  }

  void removeCurrentThread() {
    if (asyncProfiler != null) {
      asyncProfiler.removeThread(Thread.currentThread());
    }
  }

  private static one.profiler.AsyncProfiler profilerForOsAndArch(
      OperatingSystem os, Arch arch, boolean musl) throws IOException {
    String libDir =
        os.name() + (os.name().equals("macos") ? "" : (musl ? "-musl-" : "-") + arch.name());
    File localLib =
        LibraryHelper.libraryFromClasspath("/native-libs/" + libDir + "/libasyncProfiler.so");
    return one.profiler.AsyncProfiler.getInstance(localLib.getAbsolutePath());
  }

  public String getVersion() {
    return asyncProfiler.getVersion();
  }

  @Nullable
  public OngoingRecording start() {
    if (asyncProfiler != null) {
      log.debug("Starting profiling");
      try {
        return new AsyncProfilerRecording(this);
      } catch (IOException | IllegalStateException e) {
        log.debug("Failed to start async profiler recording", e);
        return null;
      }
    }
    return null;
  }

  @Nullable
  public RecordingData stop(OngoingRecording recording) {
    if (asyncProfiler != null) {
      log.debug("Stopping profiling");
      return recording.stop();
    }
    return null;
  }

  /** A call-back from {@linkplain AsyncProfilerRecording#stop()} */
  void stopProfiler() {
    if (asyncProfiler != null) {
      if (recordingFlag.compareAndSet(true, false)) {
        asyncProfiler.stop();
        if (isActive()) {
          log.debug("Profiling is still active. Waiting to stop.");
          while (isActive()) {
            LockSupport.parkNanos(10_000_000L);
          }
        }
      }
    }
  }

  public Set<ProfilingMode> enabledModes() {
    return profilingModes;
  }

  public boolean isAvailable() {
    return asyncProfiler != null;
  }

  boolean isActive() {
    if (!isAvailable()) {
      return false;
    }
    try {
      String status = executeProfilerCmd("status");
      log.debug("Async Profiler Status = {}", status);
      return !status.contains("not active");
    } catch (IOException ignored) {
    }
    return false;
  }

  String executeProfilerCmd(String cmd) throws IOException {
    return asyncProfiler.execute(cmd);
  }

  Path newRecording() throws IOException, IllegalStateException {
    if (recordingFlag.compareAndSet(false, true)) {
      Path recFile = Files.createTempFile("dd-profiler-", ".jfr");
      String cmd = cmdStartProfiling(recFile);
      try {
        String rslt = executeProfilerCmd(cmd);
        log.debug("AsyncProfiler.execute({}) = {}", cmd, rslt);
      } catch (IOException | IllegalStateException e) {
        if (log.isDebugEnabled()) {
          log.warn("Unable to start async profiler recording", e);
        } else {
          log.warn("Unable to start async profiler recording: {}", e.getMessage());
        }
        recordingFlag.set(false);
        throw e;
      }
      return recFile;
    }
    throw new IllegalStateException("Async profiler session has already been started");
  }

  String cmdStartProfiling(Path file) throws IllegalStateException {
    // 'start' = start, 'jfr=7' = store in JFR format ready for concatenation
    StringBuilder cmd = new StringBuilder("start,jfr=7");
    cmd.append(",file=").append(file.toAbsolutePath());
    cmd.append(",loglevel=").append(getLogLevel());
    cmd.append(",jstackdepth=").append(getStackDepth());
    cmd.append(",cstack=").append(getCStack());
    cmd.append(",safemode=").append(getSafeMode());
    if (profilingModes.contains(ProfilingMode.CPU)) {
      // cpu profiling is enabled.
      cmd.append(",cpu=").append(getCpuInterval()).append('m');
    }
    if (profilingModes.contains(ProfilingMode.WALL)) {
      // wall profiling is enabled.
      cmd.append(",wall=");
      if (isCollapsingWallclock()) {
        cmd.append('~'); // this prefix will turn on wall-clock collapsing feature
      }
      cmd.append(getWallInterval()).append('m');
      if (AsyncProfilerConfig.isWallThreadFilterEnabled()) {
        cmd.append(",filter=0");
      }
      cmd.append(",loglevel=").append(AsyncProfilerConfig.getLogLevel());
    }
    if (profilingModes.contains(ProfilingMode.ALLOCATION)) {
      // allocation profiling is enabled
      cmd.append(",alloc=").append(getAllocationInterval()).append('b');
    }
    if (profilingModes.contains(ProfilingMode.MEMLEAK)) {
      // memleak profiling is enabled
      cmd.append(",memleak=")
          .append(getMemleakInterval())
          .append('b')
          .append(",memleakcap=")
          .append(getMemleakCapacity())
          .append('b');
    }
    String cmdString = cmd.toString();
    log.debug("Async profiler command line: {}", cmdString);
    return cmdString;
  }

  public int getAllocationInterval() {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_ASYNC_ALLOC_INTERVAL,
        ProfilingConfig.PROFILING_ASYNC_ALLOC_INTERVAL_DEFAULT);
  }

  public int getCpuInterval() {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_ASYNC_CPU_INTERVAL,
        ProfilingConfig.PROFILING_ASYNC_CPU_INTERVAL_DEFAULT);
  }

  public int getWallInterval() {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_ASYNC_WALL_INTERVAL,
        ProfilingConfig.PROFILING_ASYNC_WALL_INTERVAL_DEFAULT);
  }

  public boolean isCollapsingWallclock() {
    return configProvider.getBoolean(
        ProfilingConfig.PROFILING_ASYNC_WALL_COLLAPSE_SAMPLES,
        ProfilingConfig.PROFILING_ASYNC_WALL_COLLAPSE_SAMPLES_DEFAULT);
  }

  private int getStackDepth() {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_ASYNC_STACKDEPTH,
        ProfilingConfig.PROFILING_ASYNC_STACKDEPTH_DEFAULT);
  }

  private int getSafeMode() {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_ASYNC_SAFEMODE, ProfilingConfig.PROFILING_ASYNC_SAFEMODE_DEFAULT);
  }

  private String getCStack() {
    return configProvider.getString(
        ProfilingConfig.PROFILING_ASYNC_CSTACK, ProfilingConfig.PROFILING_ASYNC_CSTACK_DEFAULT);
  }

  public long getMemleakInterval() {
    return configProvider.getLong(
        ProfilingConfig.PROFILING_ASYNC_MEMLEAK_INTERVAL, memleakIntervalDefault);
  }

  public int getMemleakCapacity() {
    return clamp(
        0,
        // see https://github.com/DataDog/async-profiler/blob/main/src/memleakTracer.h
        8192,
        configProvider.getInteger(
            ProfilingConfig.PROFILING_ASYNC_MEMLEAK_CAPACITY,
            ProfilingConfig.PROFILING_ASYNC_MEMLEAK_CAPACITY_DEFAULT));
  }

  private String getLogLevel() {
    if (log.isTraceEnabled()) {
      return "trace";
    }
    if (log.isDebugEnabled()) {
      return "debug";
    }
    if (log.isInfoEnabled()) {
      return "info";
    }
    if (log.isWarnEnabled()) {
      return "warn";
    }
    if (log.isErrorEnabled()) {
      return "error";
    }
    return "none";
  }

  private int clamp(int min, int max, int value) {
    return Math.max(min, Math.min(max, value));
  }

  public void setContext(long spanId, long rootSpanId) {
    if (asyncProfiler != null) {
      try {
        asyncProfiler.setContext(spanId, rootSpanId);
      } catch (IllegalStateException e) {
        log.warn("Failed to set context", e);
      }
    }
  }

  public void clearContext() {
    if (asyncProfiler != null) {
      try {
        asyncProfiler.clearContext();
      } catch (IllegalStateException e) {
        log.warn("Failed to clear context", e);
      }
    }
  }
}
