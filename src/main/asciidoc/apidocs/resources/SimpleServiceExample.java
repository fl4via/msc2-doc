import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jboss.msc.service.Dependency;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceContext;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceMode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.txn.BasicTransaction;
import org.jboss.msc.txn.CommitResult;
import org.jboss.msc.txn.CompletionListener;
import org.jboss.msc.txn.PrepareResult;
import org.jboss.msc.txn.TransactionController;

/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 *
 */
public class SimpleServiceExample {

    private static final ServiceName A_SERVICE_NAME = ServiceName.of("COMPONENT", "A");
    private static final ServiceName B_SERVICE_NAME = ServiceName.of("COMPONENT", "B");

    private final ServiceContainer serviceContainer;
    private final ServiceRegistry serviceRegistry;
    private final TransactionController txnController;
    private final ThreadPoolExecutor executor;
    private ServiceController<?> serviceBController;

    public SimpleServiceExample() {
        txnController = TransactionController.createInstance();
        serviceContainer = txnController.createServiceContainer();
        serviceRegistry = serviceContainer.newRegistry();
        executor = new ThreadPoolExecutor(8, 8, 0L, TimeUnit.DAYS, new LinkedBlockingQueue<Runnable>());
    }

    public static void main(String[] args) throws InterruptedException {
        
        final SimpleServiceExample example = new SimpleServiceExample();
        ComponentB serviceB = example.installComponentB();

        System.out.println("Service B is installed");
        serviceB.provideService();
        example.removeComponentB();
        System.out.println("Service B is removed");
        serviceB.provideService();

        example.shutdown();
    }

    private ComponentB installComponentB() {

        final BasicTransaction txn = txnController.createTransaction(executor);
        final ComponentBService componentBService;
        try {
            final ServiceContext serviceContext = txnController.getServiceContext();
            final ServiceBuilder<ComponentA> serviceABuilder = serviceContext.addService(ComponentA.class, serviceRegistry, A_SERVICE_NAME, txn);
            serviceABuilder.setService(new ComponentAService());
            serviceABuilder.setMode(ServiceMode.ON_DEMAND);
            serviceABuilder.install();

            final ServiceBuilder<ComponentB> serviceBBuilder = serviceContext.addService(ComponentB.class, serviceRegistry, B_SERVICE_NAME, txn);
            componentBService = new ComponentBService(serviceBBuilder);
            serviceBBuilder.setService(componentBService);
            serviceBBuilder.setMode(ServiceMode.ACTIVE);
            serviceBController = serviceBBuilder.install();
        } finally {
            prepareAndCommit(txn);
        }

        return componentBService.getComponentB();
    }

    private void removeComponentB() {
        final BasicTransaction txn = txnController.createTransaction(executor);
        try {
            serviceBController.remove(txn);
        } finally {
            prepareAndCommit(txn);
        }
    }

    private void shutdown() throws InterruptedException {

        final BasicTransaction txn = txnController.createTransaction(executor);
        try {
            serviceContainer.shutdown(txn);
        } finally {
            prepareAndCommit(txn);
        }
        executor.shutdown();
    }

    private void prepareAndCommit(BasicTransaction txn) {
        final CompletionListener<PrepareResult<BasicTransaction>> prepareListener = new CompletionListener<>();
        txnController.prepare(txn, prepareListener);
        try {
            prepareListener.awaitCompletion();
        } catch (InterruptedException e) {}
        final CompletionListener<CommitResult<BasicTransaction>> commitListener = new CompletionListener<>();
        txnController.commit(txn,  commitListener);
        try {
            commitListener.awaitCompletion();
        } catch (InterruptedException e) {}
    }

    private static class ComponentAService implements Service<ComponentA> {

        private ComponentA componentA;

        @Override
        public void start(StartContext<ComponentA> startContext) {
            // create and set up service value
            componentA = new ComponentA();
            componentA.start();
            startContext.complete(componentA);
        }

        @Override
        public void stop(StopContext rollbackContext) {
            componentA.stop();
            rollbackContext.complete();
        }
    }

    private static class ComponentBService implements Service<ComponentB> {

        private Dependency<ComponentA> dependencyA;
        private ComponentB componentB;

        public ComponentBService(ServiceBuilder<ComponentB> serviceBuilder) {
            dependencyA = serviceBuilder.addDependency(A_SERVICE_NAME);
        }

        @Override
        public void start(StartContext<ComponentB> startContext) {
            // retrieve component A
            ComponentA componentA = dependencyA.get();
            // create component B
            this.componentB = new ComponentB(componentA);
            // enroll component B on whatever services will make it available... here, we just provide it as a value of
            // component B service
            startContext.complete(componentB);
        }

        @Override
        public void stop(StopContext stopContext) {
            stopContext.complete();
        }

        public ComponentB getComponentB() {
            return componentB;
        }
    }

    private static class ComponentA {

        private String serviceExecution;

        public void start() {
            serviceExecution = "A"; // service is available
        }

        public void stop() {
            serviceExecution = "-"; // service is not available
        }

        public String execute() {
            return serviceExecution;
        }
    }

    private static class ComponentB {

        private ComponentA componentA;
        
        public ComponentB(ComponentA componentA) {
            this.componentA = componentA;
        }

        public void provideService() {
            // uses A to provide service
            System.out.println("B providing " + componentA.execute());
        }
    }
}