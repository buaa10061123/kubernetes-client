package io.fabric8.kubernetes.client.utils.informers.cache;

import io.fabric8.kubernetes.api.model.DoneableNamespace;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListMeta;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.Watchable;
import io.fabric8.kubernetes.client.dsl.base.BaseOperation;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.dsl.internal.NamespaceOperationsImpl;
import io.fabric8.kubernetes.client.dsl.internal.PodOperationsImpl;
import io.fabric8.kubernetes.client.utils.ReflectUtils;
import io.fabric8.kubernetes.client.utils.informers.ListerWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReflectorRunnable<T extends HasMetadata, TList extends KubernetesResourceList<T>> implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(ReflectorRunnable.class);

  private String lastSyncResourceVersion;
  private Watch watch;
  private ListerWatcher<T, TList> listerWatcher;
  private Store<T> store;
  private Class<T> apiTypeClass;
  private AtomicBoolean isActive = new AtomicBoolean(true);
  private BaseOperation baseOperation;
  private OperationContext operationContext;

  public ReflectorRunnable(Class<T> apiTypeClass, ListerWatcher listerWatcher, Store store, BaseOperation<T, TList, ?, ?> baseOperation, OperationContext operationContext) {
    this.listerWatcher = listerWatcher;
    this.store = store;
    this.apiTypeClass = apiTypeClass;
    this.baseOperation = baseOperation;
    this.operationContext = operationContext;
  }

  /**
   * Run first lists all items and get the resource version at the moment of call
   * and then use the resource version to watch.
   */
  public void run() {
    try {
      log.info("{}#Start listing and watching...", apiTypeClass);

      TList list = listerWatcher.list(new ListOptionsBuilder().withWatch(Boolean.FALSE).withResourceVersion(null).withTimeoutSeconds(null).build(), null);

      ListMeta listMeta = list.getMetadata();
      String resourceVersion = listMeta.getResourceVersion();
      List<T> items = list.getItems();

      if (log.isDebugEnabled()) {
        log.debug("{}#Extract resourceVersion {} list meta", apiTypeClass, resourceVersion);
      }
      this.syncWith(items, resourceVersion);
      this.lastSyncResourceVersion = resourceVersion;

      if (log.isDebugEnabled()) {
        log.debug("{}#Start watching with {}...", apiTypeClass, lastSyncResourceVersion);
      }

      if (!isActive.get()) {
        if (watch != null) {
          log.info("Closing watch");
          watch.close();
          return;
        }
      }
      if (watch != null) {
        log.info("Closing existing watch and waiting");
        watch.close();
      }
      try {
        // Use resource version to watch
        watch = listerWatcher.watch(new ListOptionsBuilder().withWatch(Boolean.TRUE).withResourceVersion(null).withTimeoutSeconds(null).build(),
          null, new Watcher<T>() {
            @Override
            public void eventReceived(Action action, T resource) {
              if (action == null) {
                log.error("unrecognized event {}", resource);
              }
              if (action == Action.ERROR) {
                String errorMessage = String.format("got ERROR event for ", resource.getMetadata().getName());
                log.error(errorMessage);
                throw new RuntimeException(errorMessage);
              }

              ObjectMeta meta = resource.getMetadata();
              String newResourceVersion = meta.getResourceVersion();
              switch (action) {
                case ADDED:
                  store.add(resource);
                  break;
                case MODIFIED:
                  store.update(resource);
                  break;
                case DELETED:
                  store.delete(resource);
                  break;
              }
              lastSyncResourceVersion = newResourceVersion;
              if (log.isDebugEnabled()) {
                log.debug("{}#Receiving resourceVersion {}", apiTypeClass, lastSyncResourceVersion);
              }
            }

            @Override
            public void onClose(KubernetesClientException exception) {
              log.error("Watch closing.");
              if (exception != null) {
                exception.printStackTrace();
                log.error("watch closed due to  " + exception.getMessage());
              }
            }
          });
      } catch (Throwable t) {
        log.info("{}#Watch connection got exception {}", apiTypeClass, t.getMessage());
      } finally {
//        if (watch != null) {
//          watch.close();
//          watch = null;
//        }
      }
    } catch (Exception exception) {
      log.error("Failure in list-watch: {}", exception.getMessage());
    }
  }

  public void stop() {
    isActive.set(false);
  }

  private void syncWith(List<T> items, String resourceVersion) {
    this.store.replace(items, resourceVersion);
  }

  public String getLastSyncResourceVersion() {
    return lastSyncResourceVersion;
  }

}
