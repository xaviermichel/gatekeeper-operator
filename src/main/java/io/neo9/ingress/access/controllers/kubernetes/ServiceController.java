package io.neo9.ingress.access.controllers.kubernetes;

import java.util.function.BiFunction;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.neo9.ingress.access.exceptions.ResourceNotManagedByOperatorException;
import io.neo9.ingress.access.services.ServiceExposerReconciler;
import io.neo9.ingress.access.utils.Debouncer;
import io.neo9.ingress.access.utils.RetryContext;
import io.neo9.ingress.access.utils.RetryableWatcher;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import static io.neo9.ingress.access.config.MutationLabels.EXPOSE_LABEL_KEY;
import static io.neo9.ingress.access.config.MutationLabels.EXPOSE_LABEL_VALUE;
import static io.neo9.ingress.access.utils.KubernetesUtils.getLabelValue;
import static io.neo9.ingress.access.utils.KubernetesUtils.getResourceNamespaceAndName;
import static java.util.Objects.nonNull;

@Component
@Slf4j
public class ServiceController {

	private final KubernetesClient kubernetesClient;

	private final RetryContext retryContext = new RetryContext();

	private final BiFunction<Action, Service, Void> onEventReceived;

	private final Debouncer debouncer = new Debouncer();

	private Watch serviceWatchOnLabel;

	public ServiceController(KubernetesClient kubernetesClient, ServiceExposerReconciler serviceExposerReconciler) {
		this.kubernetesClient = kubernetesClient;
		this.onEventReceived = (action, service) -> {
			String serviceNamespaceAndName = getResourceNamespaceAndName(service);
			switch (action) {
				case ADDED:
				case MODIFIED:
					boolean isToExpose = EXPOSE_LABEL_VALUE.equalsIgnoreCase(getLabelValue(EXPOSE_LABEL_KEY, service));
					log.info("update event detected for service : {} (isToExpose = {})", serviceNamespaceAndName, isToExpose);

					debouncer.debounce(serviceNamespaceAndName, () -> {
						try {
							if (isToExpose) {
								serviceExposerReconciler.reconcile(service);
							} else {
								serviceExposerReconciler.reconcileOnDelete(service);
							}
						}
						catch (ResourceNotManagedByOperatorException e) {
							log.error("panic: could not work on resource {}", e.getResourceNamespaceName(), e);
						}
					});
					break;
				case DELETED:
					log.info("delete event detected for service : {}", serviceNamespaceAndName);
					debouncer.debounce(serviceNamespaceAndName, () -> {
						try {
							serviceExposerReconciler.reconcileOnDelete(service);
						}
						catch (ResourceNotManagedByOperatorException e) {
							log.error("panic: could not work on resource {}", e.getResourceNamespaceName(), e);
						}
					});
					break;
				default:
					// do nothing on error
			}
			return null;
		};
	}

	@PostConstruct
	public void startWatch() {
		watchServicesOnLabel();
	}

	@PreDestroy
	public void stopWatch() {
		closeServicesWatchOnLabel();
		retryContext.shutdown();
		debouncer.shutdown();
	}

	private void closeServicesWatchOnLabel() {
		if (nonNull(serviceWatchOnLabel)) {
			log.info("closing watch loop on service (by label)");
			serviceWatchOnLabel.close();
			serviceWatchOnLabel = null;
		}
	}

	private void watchServicesOnLabel() {
		closeServicesWatchOnLabel();
		log.info("starting watch loop on service (by label)");
		serviceWatchOnLabel = kubernetesClient.services()
				.inAnyNamespace()
				.withLabel(EXPOSE_LABEL_KEY)
				.watch(new RetryableWatcher<>(
						retryContext,
						String.format("%s-onLabel", Service.class.getSimpleName()),
						this::watchServicesOnLabel,
						service -> true,
						onEventReceived
				));
	}

}
