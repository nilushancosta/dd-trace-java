package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.List;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/** AWS SDK v2 instrumentation */
@AutoService(Instrumenter.class)
public final class AwsClientInstrumentation extends AbstractAwsClientInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("resolveExecutionInterceptors")),
        AwsClientInstrumentation.class.getName() + "$AwsBuilderAdvice");
  }

  public static class AwsBuilderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(@Advice.Return final List<ExecutionInterceptor> interceptors) {
      for (ExecutionInterceptor interceptor : interceptors) {
        if (interceptor instanceof TracingExecutionInterceptor) {
          return; // list already has our interceptor, return to builder
        }
      }
      interceptors.add(new TracingExecutionInterceptor());
    }
  }
}
