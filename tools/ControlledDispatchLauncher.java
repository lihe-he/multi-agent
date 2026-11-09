package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.mcp.McpManager;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.SpringApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 外部 Jar 验收夹具，只替换根执行器；持久事务、HTTP、Worker 与生产代码保持实际路径。
 */
public class ControlledDispatchLauncher {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("release-marker-path");
        Path release = Path.of(args[0]).toAbsolutePath(); CountDownLatch entered = new CountDownLatch(1);
        var controlled = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("dispatch-jar-controlled-executor").factory());
        controlled.submit(() -> {
            entered.countDown();
            try { while (!Files.exists(release)) Thread.sleep(20); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        if (!entered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Controlled executor did not enter its fixture gate");
        SpringApplication application = new SpringApplication(WorkbenchApplication.class);
        application.addInitializers(context -> context.addBeanFactoryPostProcessor(factory -> {
            DefaultListableBeanFactory registry = (DefaultListableBeanFactory) factory;
            boolean previous = registry.isAllowBeanDefinitionOverriding(); registry.setAllowBeanDefinitionOverriding(true);
            try {
                RootBeanDefinition definition = new RootBeanDefinition(WorkbenchEngine.class, () -> new WorkbenchEngine(
                    factory.getBean(ExecutionStore.class), factory.getBean(WorkbenchCatalog.class), factory.getBean(WorkspaceRuntime.class),
                    factory.getBean(ResourceManager.class), factory.getBean(ObjectMapper.class), factory.getBean(McpManager.class), controlled));
                registry.registerBeanDefinition("workbenchEngine", definition);
            } finally { registry.setAllowBeanDefinitionOverriding(previous); }
        }));
        System.out.println("CONTROLLED_DISPATCH_FIXTURE engine=" + WorkbenchEngine.class.getResource("WorkbenchEngine.class"));
        try { application.run(); }
        catch (Exception exception) { controlled.shutdownNow(); throw exception; }
    }
}
