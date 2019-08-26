package io.fabric8.kubernetes.examples;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.utils.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.utils.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.utils.informers.cache.Lister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class InformerExample {
  private static Logger logger = LoggerFactory.getLogger(InformerExample.class);

  public static void main(String args[]) throws IOException, InterruptedException {
    try (final KubernetesClient client = new DefaultKubernetesClient()) {
      SharedInformerFactory sharedInformerFactory = client.informers();
      SharedIndexInformer<Pod> podInformer = sharedInformerFactory.sharedIndexInformerFor(Pod.class, PodList.class, 15 *60 * 1000);
      log("Informer factory initialized.");

      podInformer.addEventHandler(
        new ResourceEventHandler<Pod>() {
          @Override
          public void onAdd(Pod pod) {
            System.out.printf("%s pod added\n", pod.getMetadata().getName());
          }

          @Override
          public void onUpdate(Pod oldPod, Pod newPod) {
            System.out.printf("%s pod updated\n", oldPod.getMetadata().getName());
          }

          @Override
          public void onDelete(Pod pod, boolean deletedFinalStateUnknown) {
            System.out.printf("%s pod deleted \n", pod.getMetadata().getName());
          }
        }
      );

      log("Starting all registered informers");
      sharedInformerFactory.startAllRegisteredInformers();
      Pod testPod = new PodBuilder()
        .withNewMetadata().withName("myapp-pod").withLabels(Collections.singletonMap("app", "myapp-pod")).endMetadata()
        .withNewSpec()
        .addNewContainer()
        .withName("myapp-container")
        .withImage("busybox:1.28")
        .withCommand("sh", "-c", "echo The app is running!; sleep 10")
        .endContainer()
        .addNewInitContainer()
        .withName("init-myservice")
        .withImage("busybox:1.28")
        .withCommand("sh", "-c", "echo inititalizing...; sleep 5")
        .endInitContainer()
        .endSpec()
        .build();

      client.pods().inNamespace("default").create(testPod);
      log("Pod created");
      Thread.sleep(3000L);

      Lister<Pod> podLister = new Lister<> (podInformer.getIndexer(), "default");
      Pod myPod = podLister.get("myapp-pod");
      log("PodLister has " + podLister.list().size());

      if (myPod != null) {
        System.out.printf("***** myapp-pod created %s", myPod.getMetadata().getCreationTimestamp());
      }

      // Wait for some time now
      TimeUnit.MINUTES.sleep(15);

      sharedInformerFactory.stopAllRegisteredInformers();
//      Thread.sleep(3000);
//      log("All informers stoppped");
//      log("Deleting myapp-pod now..");
//      client.pods().inNamespace("default").withName("myapp-pod").delete();
    }
  }

  private static void log(String action, Object obj) {
    logger.info("{}: {}", action, obj);
  }

  private static void log(String action) {
    logger.info(action);
  }
}
